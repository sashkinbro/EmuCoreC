package com.sbro.emucorec.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sbro.emucorec.R
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

@Composable
fun InstallGameChoiceDialog(
    onDismiss: () -> Unit,
    onInstallLicense: () -> Unit,
    onInstallPkg: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).widthIn(max = 560.dp),
            shape = neonShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.install_choice_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(R.string.install_choice_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(shape = neonButtonShape(), onClick = onInstallPkg, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Inventory2, null)
                    Text(stringResource(R.string.setup_pkg_button), modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(shape = neonButtonShape(), onClick = onInstallLicense, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.VpnKey, null)
                    Text(stringResource(R.string.setup_pkg_license_button), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    text = stringResource(R.string.install_choice_pkg_without_license_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}
