package com.sbro.emucorec.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.ui.common.ScreenTopBar
import com.sbro.emucorec.ui.common.SectionCard
import com.sbro.emucorec.ui.theme.ScreenContentBottomPadding
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    ps3RootPath: String,
    onBackClick: () -> Unit,
    onInstallLicense: () -> Unit,
    onInstallPkg: () -> Unit,
) {
    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(top = topInset, bottom = ScreenContentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenTopBar(
            title = stringResource(R.string.setup_title),
            onBackClick = onBackClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding),
            titleMaxLines = 2,
        )
        SectionCard(title = stringResource(R.string.onboarding_storage_title)) {
            SetupInfoRow(Icons.Rounded.Storage, ps3RootPath)
        }
        SectionCard(title = stringResource(R.string.setup_pkg_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupInfoRow(Icons.Rounded.Inventory2, stringResource(R.string.setup_pkg_body))
                Button(shape = neonButtonShape(), onClick = onInstallPkg, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_pkg_button))
                }
                Text(
                    text = stringResource(R.string.setup_pkg_order_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard(title = stringResource(R.string.setup_pkg_license_step_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupInfoRow(Icons.Rounded.VpnKey, stringResource(R.string.setup_pkg_license_step_body))
                FilledTonalButton(shape = neonButtonShape(), onClick = onInstallLicense, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_pkg_license_button))
                }
            }
        }
    }
}

@Composable
private fun SetupInfoRow(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = neonShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
