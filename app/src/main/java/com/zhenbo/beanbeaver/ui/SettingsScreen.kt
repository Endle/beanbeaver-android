package com.zhenbo.beanbeaver.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.BuildConfig
import com.zhenbo.beanbeaver.Entitlements
import com.zhenbo.beanbeaver.debug.DebugInfoStore
import com.zhenbo.beanbeaver.export.LedgerFileOptions
import com.zhenbo.beanbeaver.export.MoneyManagerExport
import com.zhenbo.beanbeaver.receipt.BudgetPrefs
import com.zhenbo.beanbeaver.receipt.LedgerFormatPrefs
import com.zhenbo.beanbeaver.receipt.ReceiptCaptureStore
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

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
    onOpenItemRules: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenDataDump: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    var currency by remember { mutableStateOf(LedgerFormatPrefs.currency(context)) }
    var taxAccount by remember { mutableStateOf(LedgerFormatPrefs.taxAccount(context)) }
    var debugEnabled by remember { mutableStateOf(DebugInfoStore.isEnabled(context)) }
    var includeDetailsJson by remember { mutableStateOf(LedgerFileOptions.includeDetailsJson(context)) }
    var premiumEnabled by remember { mutableStateOf(Entitlements.isPremium(context)) }
    var moneyManagerAccount by remember { mutableStateOf(MoneyManagerExport.account(context)) }

    val spendRecords by SpendStore.records.collectAsStateWithLifecycle()
    val hideAmounts by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()
    // Recomputed as records change, so clearing photos updates the row in place.
    val capturedBytes = remember(spendRecords) { ReceiptCaptureStore.totalBytes(context) }
    val budgetRoots = remember { BudgetPrefs.declaredRoots(context) }
    var budgetRoot by remember { mutableStateOf(BudgetPrefs.root(context)) }
    var budgetAmount by remember {
        mutableStateOf(BudgetPrefs.monthlyAmount(context)?.let { "%.2f".format(it) } ?: "")
    }
    var confirmClearAllPhotos by remember { mutableStateOf(false) }
    var confirmDeleteAllReceipts by remember { mutableStateOf(false) }

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

            // Sits under Ledger rather than at the top of the page: it's the same
            // kind of setting — what an export writes — and it's a narrow one, so
            // it shouldn't be the first thing Settings opens on. Still its own
            // section, not folded into Ledger, because it spans every file
            // backend rather than the beancount format.
            SettingsSection(
                footer = "Store a .json alongside each exported receipt — its items, prices, and category " +
                    "tags — next to the beancount and photo. Applies to every export destination.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Save details file",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includeDetailsJson,
                        onCheckedChange = {
                            includeDetailsJson = it
                            LedgerFileOptions.setIncludeDetailsJson(context, it)
                        },
                    )
                }
            }

            SettingsSection(
                footer = "The ruleset that decides an item's tags and account. Browse what's built " +
                    "in, test a receipt line to see which rules fired, or import your own rules to " +
                    "layer on top.",
            ) {
                NavRow(title = "Categories & Tags", subtitle = null, onClick = onOpenItemRules)
            }

            if (premiumEnabled) {
                SettingsSection(
                    title = "Money Manager",
                    footer = "The account name rows are filed under when you export a receipt as a " +
                        "Money Manager spreadsheet. Match an account you've already set up there.",
                ) {
                    OutlinedTextField(
                        value = moneyManagerAccount,
                        onValueChange = {
                            moneyManagerAccount = it
                            MoneyManagerExport.setAccount(context, it)
                        },
                        label = { Text("Account name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsSection(
                title = "Budget",
                footer = "Which tracked category gets a monthly target on the Spending screen — " +
                    "computed from your scanned receipts' items, not the receipt totals. Leave the " +
                    "amount blank to track spend with no target.",
            ) {
                PresetOrCustomField(
                    label = "Budget category",
                    // Never a hardcoded list: the picker offers exactly the roots
                    // the rule corpus in force declares, so an imported document
                    // that adds a category can be budgeted the same day.
                    presets = budgetRoots.map { it.replaceFirstChar { c -> c.uppercase() } to it },
                    value = budgetRoot,
                ) {
                    budgetRoot = it
                    BudgetPrefs.setRoot(context, it)
                }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = budgetAmount,
                    onValueChange = {
                        budgetAmount = it
                        BudgetPrefs.setMonthlyAmount(context, it.toDoubleOrNull())
                    },
                    label = { Text("Monthly amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection(
                footer = "Money figures on the home card and the Spending screens read as \"$•••\" " +
                    "until you tap the eye. On by default — anyone glancing at an unlocked phone " +
                    "would otherwise read your month's total off the home screen.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Hide amounts",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = hideAmounts,
                        onCheckedChange = { AmountPrivacy.set(context, it) },
                    )
                }
            }

            // The honest successor to the old "Clear Old Receipts": no heuristic,
            // and each action says exactly what it keeps. A scanned receipt is now
            // kept until the user removes it (see SpendStore), so this is the only
            // place that storage is freed from.
            SettingsSection(
                title = "Receipts",
                footer = "Every receipt you scan is kept on this device until you remove it. " +
                    "Clearing photos frees the space but keeps every parsed receipt and every " +
                    "spend figure; deleting removes both. Anything already exported to your " +
                    "ledger is untouched either way.",
            ) {
                LabeledRow("Receipts recorded", "${spendRecords.size}")
                Spacer(Modifier.size(8.dp))
                LabeledRow("Receipt photos", formatBytes(capturedBytes))
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { confirmClearAllPhotos = true },
                    enabled = spendRecords.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All Photos", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { confirmDeleteAllReceipts = true },
                    enabled = spendRecords.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete All Receipts", fontWeight = FontWeight.SemiBold)
                }
            }

            SettingsSection(
                title = "Scanning",
                footer = "Skipping the orientation check trades correctness on upside-down text for ~22% faster " +
                    "scans — safe for upright receipts, so it is on by default. Turn it off if a scan misreads " +
                    "rotated text. Takes effect on the next scan.",
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
                footer = "Both ship inside the app, so they're readable offline.",
            ) {
                NavRow(title = "Privacy Policy", subtitle = null, onClick = onOpenPrivacy)
                Spacer(Modifier.size(8.dp))
                NavRow(title = "Acknowledgements", subtitle = null, onClick = onOpenAcknowledgements)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Enable premium features",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = premiumEnabled,
                        onCheckedChange = {
                            premiumEnabled = it
                            Entitlements.setPremium(context, it)
                        },
                    )
                }
                Spacer(Modifier.size(8.dp))
                NavRow(title = "Stored Debug Info", subtitle = null, onClick = onOpenDebug)
                Spacer(Modifier.size(8.dp))
                NavRow(title = "Dump All Data", subtitle = null, onClick = onOpenDataDump)
            }

            SettingsSection(
                title = "About",
                footer = "beanbeaver-core is the on-device scanning engine. Build is how this copy " +
                    "captures a receipt: the ML Kit builds guide the shot, the FOSS builds use " +
                    "BeanBeaver's own camera. Include all three when reporting a scan issue.",
            ) {
                LabeledRow("BeanBeaver", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledRow("Build", BuildConfig.DISTRIBUTION)
                LabeledRow("beanbeaver-core", BuildConfig.CORE_VERSION)
            }

            // Directly under About, so the two read as one move: the versions to
            // quote, then somewhere to quote them.
            SettingsSection(
                title = "Feedback",
                footer = "Questions, bugs, and receipts that came out wrong — whichever room suits " +
                    "you. When it's a scan problem, include the two versions above.",
            ) {
                FEEDBACK_ROOMS.forEachIndexed { i, room ->
                    if (i > 0) Spacer(Modifier.size(8.dp))
                    NavRow(
                        title = room.first,
                        subtitle = null,
                        // ACTION_VIEW hands the URL to the system, which is what
                        // lets an installed Discord or Element app take it and
                        // the browser handle it otherwise. Wrapped because a
                        // device with nothing able to open it would otherwise
                        // throw ActivityNotFoundException.
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(room.second)))
                            }
                        },
                    )
                }
            }
        }
    }


    if (confirmClearAllPhotos) {
        AlertDialog(
            onDismissRequest = { confirmClearAllPhotos = false },
            title = { Text("Clear all photos?") },
            text = {
                Text(
                    "Frees the space used by every receipt photo. Every receipt's parsed data " +
                        "and every spend figure stay exactly as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SpendStore.clearAllPhotos(context)
                    confirmClearAllPhotos = false
                }) { Text("Clear Photos") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAllPhotos = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmDeleteAllReceipts) {
        val n = spendRecords.size
        AlertDialog(
            onDismissRequest = { confirmDeleteAllReceipts = false },
            title = { Text("Delete all receipts?") },
            text = {
                Text(
                    "Removes the parsed data and the photos for every scanned receipt on this " +
                        "device. Anything already exported to your ledger is untouched, and " +
                        "originals stay in your photo library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SpendStore.removeAll(context)
                    confirmDeleteAllReceipts = false
                }) { Text("Delete $n Receipt${if (n == 1) "" else "s"}") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAllReceipts = false }) { Text("Cancel") }
            },
        )
    }
}

/** Common currencies, with the device locale's own pinned first if not already listed. */
private fun currencyPresets(): List<Pair<String, String>> {
    val codes = mutableListOf("CAD", "USD", "EUR", "GBP", "AUD", "JPY", "CNY")
    LedgerFormatPrefs.localeCurrency()?.let { if (it !in codes) codes.add(0, it) }
    return codes.map { it to it }
}

/** Where the project can be reached: display title to URL. */
private val FEEDBACK_ROOMS = listOf(
    "Discord" to "https://discord.gg/qsfS7uUMHQ",
    "Matrix" to "https://matrix.to/#/#beanbeaver:matrix.org",
)

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
