package com.beanbeaver.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beanbeaver.app.BuildConfig
import com.beanbeaver.app.ui.theme.groupedBackground

/**
 * The Android twin of iOS Settings. No camera/export/sync in this MVP, so it
 * carries the settings that do exist: the orientation-check speed toggle, a
 * "scan a sample" entry point, and the app / on-device-engine versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    skipOrientation: Boolean,
    onSkipOrientationChange: (Boolean) -> Unit,
    onRunSample: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section(
                title = "Scanning",
                footer = "Skipping the orientation check trades correctness on upside-down " +
                    "text for ~22% faster scans — safe for upright receipts. Takes effect on the next scan.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Skip orientation check",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = skipOrientation, onCheckedChange = onSkipOrientationChange)
                }
            }

            Section(
                footer = "Runs the full on-device scan on a receipt bundled with the app — " +
                    "a way to see what BeanBeaver does without a receipt in hand.",
            ) {
                OutlinedButton(onClick = onRunSample, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a Sample Receipt", fontWeight = FontWeight.SemiBold)
                }
            }

            Section(
                title = "About",
                footer = "beanbeaver-core is the on-device scanning engine. Include both " +
                    "versions when reporting a scan issue.",
            ) {
                LabeledRow("BeanBeaver", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledRow("beanbeaver-core", BuildConfig.CORE_VERSION)
            }
        }
    }
}

/**
 * An iOS-style grouped section: an optional uppercase header, a rounded card of
 * rows, and an optional quiet footer explaining the setting.
 */
@Composable
private fun Section(
    title: String? = null,
    footer: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        BbCard(content = content)
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
