package com.sbro.emucorec.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.data.AppPreferences
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorec.R
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.ui.common.NavigationBackButton
import com.sbro.emucorec.ui.common.SettingHelpButton
import com.sbro.emucorec.ui.common.rememberDebouncedClick
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

private val SettingsRowHorizontalPadding = ScreenHorizontalPadding
private val SettingsRowInnerHorizontalPadding = 14.dp
private val SettingsRowInnerVerticalPadding = 14.dp

enum class SettingsTab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    General(R.string.settings_tab_general, Icons.Rounded.Tune),
    Customization(R.string.settings_tab_customization, Icons.Rounded.Palette),
    Graphics(R.string.settings_tab_graphics, Icons.Rounded.GraphicEq),
    Overlay(R.string.settings_tab_overlay, Icons.Rounded.Vibration),
    Audio(R.string.settings_tab_audio, Icons.AutoMirrored.Rounded.VolumeUp),
    Controls(R.string.settings_tab_controls, Icons.Rounded.Gamepad),
    Storage(R.string.settings_tab_storage, Icons.Rounded.Storage),
    Network(R.string.settings_tab_network, Icons.Rounded.Public),
    Advanced(R.string.settings_tab_advanced, Icons.Rounded.SettingsSuggest),
    Updates(R.string.settings_tab_updates, Icons.Rounded.SystemUpdateAlt),
    About(R.string.settings_tab_about, Icons.Rounded.Info),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    initialTab: SettingsTab = SettingsTab.General,
    onBackClick: () -> Unit,
    onOpenLanguageSettings: () -> Unit,
    onOpenGpuDriverSettings: () -> Unit = {},
    onOpenTouchControlsEditor: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val defaults = remember { Ps3CoreConfig() }
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val backupCreatedMessage = stringResource(R.string.settings_backup_created)

    val gameFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val prefs = AppPreferences(context)
            prefs.addGameDirectory(uri.toString())
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            InstallStateBus.notifyCompleted()
            Toast.makeText(context, R.string.direct_boot_added_success, Toast.LENGTH_SHORT).show()
        }
    }
    val backupFailedMessage = stringResource(R.string.settings_backup_failed)
    val restoreCompletedMessage = stringResource(R.string.settings_backup_restored)
    val restoreFailedMessage = stringResource(R.string.settings_backup_restore_failed)

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportSettingsBackup(uri) { result ->
            Toast.makeText(
                context,
                if (result.isSuccess) backupCreatedMessage else backupFailedMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.restoreSettingsBackup(uri) { result ->
            Toast.makeText(
                context,
                if (result.isSuccess) restoreCompletedMessage else restoreFailedMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val createBackupClick = rememberDebouncedClick { backupPicker.launch("emucorec-settings-backup.json") }
    val backClick = rememberDebouncedClick(onClick = onBackClick)
    val resetSettingsClick = rememberDebouncedClick(onClick = viewModel::resetCoreSettingsToDefaults)
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreBackupDialog by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsCompactTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(selectedTab.titleRes),
                topInset = topInset,
                onBackClick = backClick,
                onResetSettingsClick = { showResetDialog = true }
            )

            SettingsTabRow(
                selectedTab = selectedTab,
                onSelected = { selectedTab = it }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    SettingsTabContent(
                        selectedTab = selectedTab,
                        uiState = uiState,
                        defaults = defaults,
                        viewModel = viewModel,
                        onOpenLanguageSettings = onOpenLanguageSettings,
                        onOpenGpuDriverSettings = onOpenGpuDriverSettings,
                        onOpenTouchControlsEditor = onOpenTouchControlsEditor,
                        onAddGameFolder = { gameFolderLauncher.launch(null) },
                        createBackupClick = createBackupClick,
                        restoreBackupClick = { showRestoreBackupDialog = true }
                    )
                }
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null
                    )
                },
                title = { Text(stringResource(R.string.settings_reset_defaults_title)) },
                text = { Text(stringResource(R.string.settings_reset_defaults_message)) },
                confirmButton = {
                    Button(shape = neonButtonShape(), 
                        onClick = {
                            showResetDialog = false
                            resetSettingsClick()
                        }
                    ) {
                        Text(stringResource(R.string.settings_reset_defaults_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(stringResource(R.string.settings_reset_defaults_cancel))
                    }
                }
            )
        }

        if (showRestoreBackupDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreBackupDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null
                    )
                },
                title = { Text(stringResource(R.string.settings_backup_restore_title)) },
                text = { Text(stringResource(R.string.settings_backup_restore_message)) },
                confirmButton = {
                    Button(shape = neonButtonShape(), 
                        onClick = {
                            showRestoreBackupDialog = false
                            restorePicker.launch(arrayOf("application/json", "text/json", "*/*"))
                        }
                    ) {
                        Text(stringResource(R.string.settings_backup_restore_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreBackupDialog = false }) {
                        Text(stringResource(R.string.settings_updates_cancel))
                    }
                }
            )
        }
    }
}

fun settingsTabFromRoute(value: String?): SettingsTab {
    return SettingsTab.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SettingsTab.General
}

@Composable
private fun SettingsCompactTopBar(
    title: String,
    subtitle: String,
    topInset: androidx.compose.ui.unit.Dp,
    onBackClick: () -> Unit,
    onResetSettingsClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = topInset,
                bottom = 4.dp
            ),
        shape = neonShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationBackButton(
                onClick = onBackClick,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                SettingsHeaderIconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.settings_options_content_description),
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_reset_defaults_menu)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onResetSettingsClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHeaderIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = neonShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 5.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        onClick = rememberDebouncedClick(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsTabRow(
    selectedTab: SettingsTab,
    onSelected: (SettingsTab) -> Unit
) {
    val tabs = remember { SettingsTab.entries.toList() }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedTab) {
        val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
        var selectedItem = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == selectedIndex }
        if (selectedItem == null) {
            listState.scrollToItem(selectedIndex)
            withFrameNanos { }
            selectedItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == selectedIndex }
        }

        selectedItem?.let { item ->
            val layoutInfo = listState.layoutInfo
            val delta = centeredTabScrollDelta(
                itemOffset = item.offset,
                itemSize = item.size,
                viewportStart = layoutInfo.viewportStartOffset,
                viewportEnd = layoutInfo.viewportEndOffset
            )
            if (kotlin.math.abs(delta) > 1f) {
                listState.animateScrollBy(delta)
            }
        }
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = tabs, key = { it.name }) { tab ->
            FilterChip(shape = neonChipShape(), 
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                label = { Text(stringResource(tab.titleRes), maxLines = 1, softWrap = false) },
                leadingIcon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}





private fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val bleedPx = bleed.roundToPx()
    val looseConstraints = constraints.copy(
        maxWidth = (constraints.maxWidth + bleedPx * 2).coerceAtLeast(0)
    )
    val placeable = measurable.measure(looseConstraints)
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(-bleedPx, 0)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onResetDefault: () -> Unit,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val resetToastMessage = stringResource(R.string.settings_reset_toast, title)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (enabled) onCheckedChange(!checked) },
                onLongClick = {
                    if (enabled) {
                        onResetDefault()
                        Toast.makeText(context, resetToastMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            ),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsRowInnerHorizontalPadding, vertical = SettingsRowInnerVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SettingHelpButton(title = title, description = description)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingChoiceRow(
    title: String,
    description: String,
    onResetDefault: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val context = LocalContext.current
    val resetToastMessage = stringResource(R.string.settings_reset_toast, title)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        onResetDefault()
                        Toast.makeText(context, resetToastMessage, Toast.LENGTH_SHORT).show()
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            SettingHelpButton(title = title, description = description)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalBleed(14.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingSliderRow(
    title: String,
    description: String,
    valueText: String,
    onResetDefault: () -> Unit,
    slider: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resetToastMessage = stringResource(R.string.settings_reset_toast, title)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = {
                    onResetDefault()
                    Toast.makeText(context, resetToastMessage, Toast.LENGTH_SHORT).show()
                }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SettingHelpButton(title = title, description = description)
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Box(modifier = Modifier.padding(top = 4.dp)) {
            slider()
        }
    }
}
