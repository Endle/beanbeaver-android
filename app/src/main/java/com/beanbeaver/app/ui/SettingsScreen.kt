package com.beanbeaver.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beanbeaver.app.BuildConfig
import com.beanbeaver.app.debug.DebugInfoStore
import com.beanbeaver.app.receipt.LedgerFormatPrefs
import com.beanbeaver.app.ui.theme.groupedBackground

/**
 * The Android twin of iOS Settings. Carries what this MVP supports: the GitHub
 * sync entry point, ledger output prefs (currency + tax account), the
 * orientation-check speed toggle, a sample scan, the debug-info capture toggle +
 * viewer, and app/core versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    skipOrientation: Boolean,
    onSkipOrientationChange: (Boolean) -> Unit,
    onRunSample: () -> Unit,
    githubConnected: Boolean,
    githubAccount: String?,
    onOpenGitHub: () -> Unit,
    onOpenDebug: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var currency by remember { mutableStateOf(LedgerFormatPrefs.currency(context)) }
    var taxAccount by remember { mutableStateOf(LedgerFormatPrefs.taxAccount(context)) }
    var debugEnabled by remember { mutableStateOf(DebugInfoStore.isEnabled(context)) }

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
            SettingsSection(
                title = "Sync",
                footer = "File each scanned receipt into your ledger repo as a GitHub pull request.",
            ) {
                NavRow(
                    title = "GitHub",
                    subtitle = if (githubConnected) githubAccount?.let { "Connected as @$it" } ?: "Connected"
                    else "Not connected",
                    onClick = onOpenGitHub,
                )
            }

            SettingsSection(
                title = "Ledger",
                footer = "The currency and tax account used in every beancount entry BeanBeaver generates. " +
                    "Currency defaults to your region. Takes effect on the next scan.",
            ) {
                PresetOrCustomField(
                    label = "Currency",
                    presets = currencyPresets(),
                    value = currency,
                    uppercase = true,
                ) {
                    currency = it
                    LedgerFormatPrefs.setCurrency(context, it)
                }
                Spacer(Modifier.size(12.dp))
                PresetOrCustomField(
                    label = "Sales tax",
                    presets = TAX_PRESETS,
                    value = taxAccount,
                ) {
                    taxAccount = it
                    LedgerFormatPrefs.setTaxAccount(context, it)
                }
            }

            SettingsSection(
                title = "Scanning",
                footer = "Skipping the orientation check trades correctness on upside-down text for ~22% faster " +
                    "scans — safe for upright receipts. Takes effect on the next scan.",
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

            SettingsSection(
                footer = "Runs the full on-device scan on a receipt bundled with the app — " +
                    "a way to see what BeanBeaver does without a receipt in hand.",
            ) {
                OutlinedButton(onClick = onRunSample, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a Sample Receipt", fontWeight = FontWeight.SemiBold)
                }
            }

            SettingsSection(
                title = "Debug",
                footer = "Off by default — keep it that way unless support asked you to turn it on. When enabled, " +
                    "BeanBeaver keeps a full copy of each scanned receipt (merchant, items, prices, the raw OCR text, " +
                    "and the generated ledger entry) plus export errors in a debug log on this device. " +
                    "The raw OCR text can include anything printed on the receipt. Turn it off again when done.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Store detailed debug info",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = debugEnabled,
                        onCheckedChange = {
                            debugEnabled = it
                            DebugInfoStore.setEnabled(context, it)
                        },
                    )
                }
                Spacer(Modifier.size(8.dp))
                NavRow(title = "Stored Debug Info", subtitle = null, onClick = onOpenDebug)
            }

            SettingsSection(
                title = "About",
                footer = "beanbeaver-core is the on-device scanning engine. Include both versions when reporting a scan issue.",
            ) {
                LabeledRow("BeanBeaver", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledRow("beanbeaver-core", BuildConfig.CORE_VERSION)
            }
        }
    }
}

/** Common currencies, with the device locale's own pinned first if not already listed. */
private fun currencyPresets(): List<Pair<String, String>> {
    val codes = mutableListOf("CAD", "USD", "EUR", "GBP", "AUD", "JPY", "CNY")
    LedgerFormatPrefs.localeCurrency()?.let { if (it !in codes) codes.add(0, it) }
    return codes.map { it to it }
}

private val TAX_PRESETS = listOf(
    "HST (Canada)" to "Expenses:Tax:HST",
    "GST" to "Expenses:Tax:GST",
    "PST" to "Expenses:Tax:PST",
    "VAT" to "Expenses:Tax:VAT",
    "Sales tax" to "Expenses:Tax:Sales",
)

/**
 * A dropdown over `presets` (display label → stored value) plus a "Custom…"
 * escape hatch that reveals a free-text field. Binds to a single stored string,
 * so a preset and a hand-typed value are the same setting. Android twin of iOS
 * `PresetOrCustomPicker`.
 */
@Composable
private fun PresetOrCustomField(
    label: String,
    presets: List<Pair<String, String>>,
    value: String,
    uppercase: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val isPreset = presets.any { it.second == value }
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = presets.firstOrNull { it.second == value }?.first ?: "Custom…"

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(currentLabel, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { (display, stored) ->
                    DropdownMenuItem(text = { Text(display) }, onClick = {
                        expanded = false
                        onValueChange(stored)
                    })
                }
                DropdownMenuItem(text = { Text("Custom…") }, onClick = {
                    expanded = false
                    if (isPreset) onValueChange("") // start the custom field empty
                })
            }
        }
        if (!isPreset) {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(if (uppercase) it.uppercase() else it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
