package com.zhenbo.beanbeaver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Photo
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
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.BuildConfig
import com.zhenbo.beanbeaver.Entitlements
import com.zhenbo.beanbeaver.debug.DebugInfoStore
import com.zhenbo.beanbeaver.export.LedgerFileOptions
import com.zhenbo.beanbeaver.export.MoneyManagerExport
import com.zhenbo.beanbeaver.receipt.AmountPrivacy
import com.zhenbo.beanbeaver.receipt.BudgetPrefs
import com.zhenbo.beanbeaver.receipt.LedgerFormatPrefs
import com.zhenbo.beanbeaver.receipt.PhotoSaver
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    SpendStore.ensureLoaded(context)
    val spendRecords by SpendStore.records.collectAsStateWithLifecycle()

    var currency by remember { mutableStateOf(LedgerFormatPrefs.currency(context)) }
    var taxAccount by remember { mutableStateOf(LedgerFormatPrefs.taxAccount(context)) }
    var debugEnabled by remember { mutableStateOf(DebugInfoStore.isEnabled(context)) }
    var includeDetailsJson by remember { mutableStateOf(LedgerFileOptions.includeDetailsJson(context)) }
    var saveToPhotos by remember { mutableStateOf(PhotoSaver.isEnabled(context)) }
    var premiumEnabled by remember { mutableStateOf(Entitlements.isPremium(context)) }
    var moneyManagerAccount by remember { mutableStateOf(MoneyManagerExport.account(context)) }
    var hideAmounts by remember { mutableStateOf(AmountPrivacy.hideAmounts(context)) }
    var budgetRoot by remember { mutableStateOf(BudgetPrefs.root(context)) }
    var budgetAmountText by remember {
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
                footer = "Keep a copy of each camera scan in your device's photo library, under a " +
                    "BeanBeaver album. Photos you import were already in the library, so they aren't " +
                    "saved again.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Save a copy to Photos",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = saveToPhotos,
                        onCheckedChange = {
                            saveToPhotos = it
                            PhotoSaver.setEnabled(context, it)
                        },
                    )
                }
            }

            SettingsSection(
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

            // Sits right under Ledger rather than at the top of the page: it's the
            // same kind of setting — what an export writes — and it's a narrow one.
            // Still its own section, not folded into Ledger, because it spans every
            // file backend rather than the beancount format.
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
                footer = "See how items are sorted into accounts, check why a particular item was " +
                    "categorized the way it was, and bring in your own rules.",
            ) {
                NavRow(
                    title = "Categories & Tags",
                    subtitle = "Tags · Accounts · Rules",
                    onClick = onOpenItemRules,
                )
            }

            SettingsSection(
                title = "Budget",
                footer = "Which tracked category gets a monthly target on the Spending screen — " +
                    "computed from your scanned receipts' items, not the receipt totals. Leave the " +
                    "amount blank to track spend with no target.\n\n" +
                    "Hide amounts covers every figure on the home card and the spending screens, so a " +
                    "glance at your phone doesn't read your month. On by default. The eye on the home " +
                    "card and on the Spending screen is this same switch, so flipping it anywhere " +
                    "changes it everywhere. Your receipts and exports are unchanged either way.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Hide amounts",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = hideAmounts,
                        onCheckedChange = {
                            hideAmounts = it
                            AmountPrivacy.setHideAmounts(context, it)
                        },
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text("Budget category", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                BudgetRootPicker(
                    roots = BudgetPrefs.declaredRoots(context),
                    selected = budgetRoot,
                    onSelect = {
                        budgetRoot = it
                        BudgetPrefs.setRoot(context, it)
                    },
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = budgetAmountText,
                    onValueChange = { newValue ->
                        budgetAmountText = newValue.filter { ch -> ch.isDigit() || ch == '.' }
                        BudgetPrefs.setMonthlyAmount(context, newValue.toDoubleOrNull())
                    },
                    label = { Text("Monthly amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

            // The honest successor to the old "Clear Old Receipts": no heuristic,
            // and each action says exactly what it keeps. A scanned receipt itself
            // is now kept until the user removes it (SpendStore), so this is the
            // only place that storage is freed from.
            SettingsSection(
                title = "Receipts",
                footer = "Clear All Photos frees the space used by every receipt photo — every " +
                    "receipt's parsed data and every spend figure stay exactly as they are. Delete All " +
                    "Receipts removes the parsed data and the photos for every scanned receipt on this " +
                    "device; anything already exported to your ledger is untouched, and originals stay " +
                    "in your photo library.",
            ) {
                LabeledRow("Receipts recorded", spendRecords.size.toString())
                Spacer(Modifier.size(4.dp))
                LabeledRow("Receipt photos", formatBytes(SpendStore.totalPhotoBytes(context)))
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { confirmClearAllPhotos = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Photo, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All Photos", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { confirmDeleteAllReceipts = true },
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
                footer = "beanbeaver-core is the on-device scanning engine. Include both versions when reporting a scan issue.",
            ) {
                LabeledRow("BeanBeaver", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledRow("beanbeaver-core", BuildConfig.CORE_VERSION)
            }

            // Where to reach the project. Placed directly under About so the two
            // read as one move: the versions to quote, then somewhere to quote them.
            SettingsSection(
                title = "Feedback",
                footer = "Questions, bugs, and receipts that came out wrong — whichever room suits you. " +
                    "When it's a scan problem, include the two versions above.",
            ) {
                NavRow(
                    title = "Discord",
                    subtitle = "discord.gg/qsfS7uUMHQ",
                    onClick = { openUrl(context, "https://discord.gg/qsfS7uUMHQ") },
                )
                Spacer(Modifier.size(8.dp))
                NavRow(
                    title = "Matrix",
                    subtitle = "matrix.to/#/#beanbeaver:matrix.org",
                    onClick = { openUrl(context, "https://matrix.to/#/#beanbeaver:matrix.org") },
                )
            }
        }
    }

    if (confirmClearAllPhotos) {
        AlertDialog(
            onDismissRequest = { confirmClearAllPhotos = false },
            title = { Text("Clear all photos?") },
            text = {
                Text(
                    "Frees the space used by every receipt photo. Every receipt's parsed data and " +
                        "every spend figure stay exactly as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAllPhotos = false
                    SpendStore.clearAllPhotos(context)
                }) { Text("Clear Photos") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAllPhotos = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteAllReceipts) {
        val count = spendRecords.size
        AlertDialog(
            onDismissRequest = { confirmDeleteAllReceipts = false },
            title = { Text("Delete all receipts?") },
            text = {
                Text(
                    "Removes the parsed data and the photos for every scanned receipt on this device. " +
                        "Anything already exported to your ledger is untouched, and originals stay in " +
                        "your photo library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAllReceipts = false
                    SpendStore.removeAll(context)
                }) { Text("Delete $count Receipt${if (count == 1) "" else "s"}") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAllReceipts = false }) { Text("Cancel") } },
        )
    }
}

/**
 * A dropdown over the declared budget root tags — the roots `RuleBook.tags()`
 * actually declares, never a hardcoded category list (iOS `BudgetPrefs.root`
 * picker). Unlike `PresetOrCustomField` there is deliberately no free-text
 * escape: a target that isn't a declared root would never match a group.
 */
@Composable
private fun BudgetRootPicker(roots: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected.replaceFirstChar { it.uppercase() },
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roots.forEach { root ->
                DropdownMenuItem(
                    text = { Text(root.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        expanded = false
                        onSelect(root)
                    },
                )
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

/**
 * Hand a URL to the system — the Android analog of iOS `Link`, which is what
 * lets the platform open the Discord/Element app when installed and fall back to
 * a browser. A typo'd URL is reported in place rather than crashing, so a bad
 * row drops to a toast instead of trapping the screen.
 */
private fun openUrl(context: Context, url: String) {
    val intent = runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(url)) }.getOrNull()
    if (intent == null) {
        Toast.makeText(context, "Couldn't open that link.", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No app can open that link.", Toast.LENGTH_SHORT).show()
    }
}
