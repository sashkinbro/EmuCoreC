@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sbro.emucorec.ui.emulation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.Ps3Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private object PortalFigures {
    val placed = mutableStateMapOf<Pair<Int, Int>, String>()
    private val folders = listOf("skylanders", "infinity", "dimensions")
    private val minimumSizes = listOf(0x400, 0x140, 0xb4)
    private val extensions = listOf(setOf("sky", "bin", "dmp", "dump"), setOf("bin", "dmp"), setOf("bin", "dmp"))

    fun folder(context: Context, kind: Int): File = File(EmulatorStorage.ps3Root(context), folders[kind]).apply { mkdirs() }
    fun isFigureName(kind: Int, name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions[kind]
    fun isFigureFile(kind: Int, file: File): Boolean = file.isFile && isFigureName(kind, file.name) && file.length() in minimumSizes[kind].toLong()..65536L

    fun characters(context: Context, kind: Int): List<ToyPortalCharacter> = runCatching {
        context.assets.open("portal/${folders[kind]}.tsv").bufferedReader().useLines { lines ->
            parsePortalCharacters(lines, kind)
        }
    }.getOrDefault(emptyList())

    fun create(context: Context, kind: Int, character: ToyPortalCharacter): File? {
        val directory = folder(context, kind)
        val safe = character.name.map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().take(90).ifBlank { "figure" }
        val extension = if (kind == 0) "sky" else "bin"
        for (suffix in 1..1000) {
            val name = if (suffix == 1) "$safe.$extension" else "$safe ($suffix).$extension"
            val file = File(directory, name)
            if (file.exists()) continue
            if (Ps3Core.instance.portalCreate(kind, file.absolutePath, character.id, character.variant)) return file
            if (!file.exists()) return null
        }
        return null
    }

    fun importFigure(context: Context, kind: Int, uri: Uri): File? = runCatching {
        val rawName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val archive = rawName.substringAfterLast('.', "").equals("zip", ignoreCase = true)
        if (!archive && !isFigureName(kind, rawName)) return null
        context.contentResolver.openInputStream(uri)?.use {
            if (archive) ToyPortalFiles.importArchive(folder(context, kind), it, minimumSizes[kind], extensions[kind]).firstOrNull()
            else ToyPortalFiles.store(folder(context, kind), rawName, it, minimumSizes[kind])
        }
    }.getOrNull()
}

@Composable
internal fun ToyPortalPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableIntStateOf(0) }
    var realPortals by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun place(kind: Int, position: Int, file: File) {
        scope.launch {
            val accepted = withContext(Dispatchers.IO) { runCatching { Ps3Core.instance.portalLoad(kind, position, file.absolutePath) == position }.getOrDefault(false) }
            if (accepted) PortalFigures.placed[kind to position] = file.name else error = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = selecting
        selecting = null
        if (uri != null && target != null) scope.launch {
            val file = withContext(Dispatchers.IO) { PortalFigures.importFigure(context, target.first, uri) }
            revision++
            if (file == null) error = true else place(target.first, target.second, file)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            realPortals = runCatching { Ps3Core.instance.usbPassedThrough() }.getOrDefault(0)
            delay(2000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Skylanders", "Disney Infinity", "LEGO Dimensions").forEachIndexed { index, title ->
                FilterChip(selected = kind == index, onClick = { kind = index }, label = { Text(title) })
            }
        }
        if (realPortals and (1 shl kind) != 0) {
            Text(stringResource(R.string.emulation_portal_real_active))
        } else {
            repeat(listOf(8, 9, 7)[kind]) { position ->
                val name = PortalFigures.placed[kind to position]
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.emulation_savestate_slot_label, position + 1) + (name?.let { ": $it" } ?: ""), modifier = Modifier.weight(1f))
                    TextButton(onClick = { selecting = kind to position }) { Text(stringResource(R.string.save_manager_import)) }
                    if (name != null) TextButton(onClick = {
                        if (runCatching { Ps3Core.instance.portalRemove(kind, position) }.getOrDefault(false)) PortalFigures.placed.remove(kind to position)
                        else error = true
                    }) { Text(stringResource(R.string.settings_gpu_driver_remove_short)) }
                }
            }
        }
        if (error) Text(stringResource(R.string.emulation_portal_import_failed), color = MaterialTheme.colorScheme.error)
    }
    selecting?.takeUnless { creating }?.let { (selectedKind, position) ->
        val files = remember(selectedKind, revision) { PortalFigures.folder(context, selectedKind).listFiles()?.filter { PortalFigures.isFigureFile(selectedKind, it) }?.sortedBy { it.name }.orEmpty() }
        AlertDialog(
            onDismissRequest = { selecting = null },
            title = { Text(stringResource(R.string.emulation_savestate_slot_label, position + 1)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { file -> TextButton(onClick = { selecting = null; place(selectedKind, position, file) }) { Text(file.name) } }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { creating = true; query = "" }) { Text(stringResource(R.string.emulation_portal_create)) }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.save_manager_import)) }
                }
            },
            dismissButton = { TextButton(onClick = { selecting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (creating) selecting?.let { (selectedKind, position) ->
        val characters = remember(selectedKind) { PortalFigures.characters(context, selectedKind) }
        val matches = remember(characters, query) { characters.filter { it.name.contains(query, ignoreCase = true) } }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.emulation_portal_create)) },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.emulation_portal_search)) }, singleLine = true)
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(matches) { character ->
                            TextButton(onClick = {
                                creating = false
                                selecting = null
                                scope.launch {
                                    val file = withContext(Dispatchers.IO) { runCatching { PortalFigures.create(context, selectedKind, character) }.getOrNull() }
                                    revision++
                                    if (file == null) error = true else place(selectedKind, position, file)
                                }
                            }) { Text(if (selectedKind == 1) "${character.name} (${character.variant}.0)" else character.name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
