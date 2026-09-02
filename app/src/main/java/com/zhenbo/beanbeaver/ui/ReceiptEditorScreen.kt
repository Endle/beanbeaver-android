package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhenbo.beanbeaver.receipt.EditedItemDraft
import com.zhenbo.beanbeaver.receipt.ItemRuleStore
import com.zhenbo.beanbeaver.receipt.LedgerFormatPrefs
import com.zhenbo.beanbeaver.receipt.ReceiptEditDraft
import com.zhenbo.beanbeaver.receipt.ReceiptIdentity
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.bbCanvas
import com.zhenbo.beanbeaver.ui.theme.bbInk
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary
import com.zhenbo.beanbeaver.ui.theme.bbInkTertiary
import uniffi.bb_receipt_ffi.DateYmd
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.reformatReceipt
import java.io.File
import java.time.LocalDate

/** The account a card purchase posts against, matching the scan pipeline's own. */
private const val CREDIT_CARD_ACCOUNT = "Liabilities:CreditCard"

/**
 * Review & Fix: correct what the parse got wrong, and re-render the receipt from
 * the correction. Kotlin twin of iOS `ReceiptEditorView`.
 *
 * The screen is a form, not a receipt card. A card is the app's way of showing a
 * receipt as a piece of paper, and this is the opposite gesture — the user is not
 * reading a receipt here, they are telling the app that it misread one.
 *
 * Nothing is corrected locally: the edits go back through [reformatReceipt], so
 * the beancount, the account each line posts to, the tags, the confidences and
 * the warnings are all re-derived by the same code that produced them in the
 * first place. That is what stops the app from showing one thing and the ledger
 * from saying another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptEditorScreen(
    original: ReceiptResult,
    /**
     * The receipt's photo, used only to re-hash it when the beancount carries no
     * `beanbeaver-image-sha256` line. See [ReceiptIdentity].
     */
    imageFile: File? = null,
    /**
     * Set when this receipt has already reached a ledger, so the screen can say
     * that editing it here does not go back and change what was filed.
     */
    exportedAt: Long? = null,
    onSave: (ReceiptResult) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(original) { mutableStateOf(ReceiptEditDraft.of(original)) }
    var editingItem by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val tags = remember { ItemRuleStore.book.value?.tags() ?: emptyList() }

    val leave = { if (draft.hasChanges) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    // One line, opened for correction. A pushed screen rather than an inline
    // expansion: a row carries four editable fields plus a category picker, and
    // none of them fit beside the others in a list row.
    editingItem?.let { id ->
        val item = draft.items.firstOrNull { it.id == id }
        if (item != null) {
            ItemEditorScreen(
                item = item,
                tags = tags,
                onChange = { updated ->
                    draft = draft.copy(
                        items = draft.items.map { if (it.id == id) updated else it },
                    )
                },
                onDelete = {
                    draft = draft.copy(items = draft.items.filterNot { it.id == id })
                    editingItem = null
                },
                onBack = { editingItem = null },
            )
            return
        }
        editingItem = null
    }

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                title = { Text("Review & Fix") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bbCanvas),
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val problem = draft.itemProblem
                            if (problem != null) {
                                saveError = problem
                                return@TextButton
                            }
                            // Nothing to send is not an error: a user who opened
                            // this, looked, and changed their mind gets the same
                            // exit as Cancel rather than a pointless re-render.
                            val edits = draft.edits()
                            if (edits == null) {
                                onBack()
                                return@TextButton
                            }
                            val now = LocalDate.now()
                            runCatching {
                                reformatReceipt(
                                    original,
                                    DateYmd(now.year, now.monthValue.toUInt(), now.dayOfMonth.toUInt()),
                                    CREDIT_CARD_ACCOUNT,
                                    LedgerFormatPrefs.currency(context),
                                    LedgerFormatPrefs.taxAccount(context),
                                    ReceiptIdentity.imageSha256(original, imageFile),
                                    edits,
                                    ItemRuleStore.parseOptions(context),
                                )
                            }.onSuccess {
                                onSave(it)
                                onBack()
                            }.onFailure { saveError = it.message ?: it.toString() }
                        },
                        enabled = draft.hasChanges,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSection(title = "Receipt") {
                OutlinedTextField(
                    value = draft.merchant,
                    onValueChange = { draft = draft.copy(merchant = it) },
                    label = { Text("Merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                // A receipt with no date is a real state the parser reports, and
                // giving it one here is the whole fix. There is no way to take a
                // date *away* through `ReceiptEdits`, so this offers the
                // direction that exists rather than a control that would
                // half-work.
                OutlinedTextField(
                    value = draft.dateIso ?: "",
                    onValueChange = { text ->
                        draft = draft.copy(
                            date = runCatching { LocalDate.parse(text) }.getOrNull() ?: draft.date,
                        )
                    },
                    label = { Text("Date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.date == null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { draft = draft.copy(date = LocalDate.now()) }) {
                        Text("Use today's date")
                    }
                }
            }

            SettingsSection(
                title = "Items (${draft.items.size})",
                footer = "Tap a line to correct or delete it. A line you rename is re-filed " +
                    "from its new text.",
            ) {
                draft.items.forEachIndexed { index, item ->
                    if (index > 0) BbHairline(startInset = 0.dp)
                    ItemRow(item = item, onClick = { editingItem = item.id })
                }
                if (draft.items.isNotEmpty()) BbHairline(startInset = 0.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val blank = EditedItemDraft.blank()
                            draft = draft.copy(items = draft.items + blank)
                            editingItem = blank.id
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, tint = BbAccent)
                    Text("Add Item", color = BbAccent, fontWeight = FontWeight.SemiBold)
                }
            }

            SettingsSection(title = "Summary") {
                AmountField("Subtotal", draft.subtotal) { draft = draft.copy(subtotal = it) }
                Spacer(Modifier.height(12.dp))
                AmountField("Tax", draft.tax) { draft = draft.copy(tax = it) }
                Spacer(Modifier.height(12.dp))
                AmountField("Total", draft.total) { draft = draft.copy(total = it) }
                Spacer(Modifier.height(10.dp))
                Reconciliation(draft)
            }

            if (exportedAt != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = bbInkTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "This receipt has already been filed. Correcting it here updates the " +
                            "app, not the entry that was exported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = bbInkSecondary,
                    )
                }
            }
        }
    }

    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text("Couldn't apply the correction") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text("Discard Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep Editing") }
            },
        )
    }
}

@Composable
private fun AmountField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("0.00") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The two identities a receipt has to satisfy, checked live.
 *
 * The lines must add to the subtotal, and subtotal plus tax must be the total.
 * It is here because it is the fastest way to see *which* line is still wrong. It
 * never blocks saving: a receipt can be mid-correction, and a receipt can also
 * genuinely not balance, which is worth being able to record.
 */
@Composable
private fun Reconciliation(draft: ReceiptEditDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        draft.itemsDifference?.let {
            CheckRow("Items add to ${formatCurrency(draft.itemsSum)}", it, "off the subtotal")
        }
        draft.summaryDifference?.let {
            CheckRow("Subtotal + tax", it, "off the total")
        }
    }
}

@Composable
private fun CheckRow(label: String, difference: Double, offBy: String) {
    // A cent of slack: the parse carries two-decimal strings and the sum of
    // several is a float, so an exactly-balanced receipt can land a rounding step
    // away from zero.
    val balanced = kotlin.math.abs(difference) < 0.005
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (balanced) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = if (balanced) bbInkSecondary else BbAccent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (balanced) "$label — balances"
            else "$label — ${formatCurrency(kotlin.math.abs(difference))} $offBy",
            style = MaterialTheme.typography.labelSmall,
            color = if (balanced) bbInkSecondary else BbAccent,
        )
    }
}

/**
 * One line as it appears in the editor's list: what it says, what it costs, and
 * where it is filed.
 */
@Composable
private fun ItemRow(item: EditedItemDraft, onClick: () -> Unit) {
    // A user-picked tag wins the label; otherwise the parse's own classification
    // stands, and a line the parse never classified says nothing rather than
    // inventing a category for it.
    val category = item.tagPath.ifEmpty { item.parsedCategory ?: "" }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.description.ifEmpty { "New item" },
                color = if (item.description.isEmpty()) bbInkSecondary else bbInk,
            )
            if (category.isNotEmpty()) {
                Text(category, style = MaterialTheme.typography.labelSmall, color = bbInkSecondary)
            }
        }
        Text(
            if (item.price.isEmpty()) "—" else formatPrice(item.price).text,
            fontFamily = FontFamily.Monospace,
            color = if (item.price.isEmpty()) bbInkSecondary else bbInk,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = bbInkTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One line of the item block, opened for correction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditorScreen(
    item: EditedItemDraft,
    tags: List<ItemTag>,
    onChange: (EditedItemDraft) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var pickingCategory by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    if (pickingCategory) {
        TagPickerScreen(
            selection = item.tagPath,
            tags = tags,
            onSelect = { onChange(item.copy(tagPath = it)); pickingCategory = false },
            onBack = { pickingCategory = false },
        )
        return
    }

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                title = { Text("Item") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bbCanvas),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Done")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete item", tint = BbAccent)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSection(title = "Description") {
                OutlinedTextField(
                    value = item.description,
                    onValueChange = { onChange(item.copy(description = it)) },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection {
                AmountField("Price", item.price) { onChange(item.copy(price = it)) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", modifier = Modifier.weight(1f), color = bbInk)
                    TextButton(
                        onClick = { onChange(item.copy(quantity = (item.quantity - 1).coerceAtLeast(1))) },
                        enabled = item.quantity > 1,
                    ) { Text("−", fontSize = 20.sp) }
                    Text(
                        "${item.quantity}",
                        fontFamily = FontFamily.Monospace,
                        color = bbInk,
                        modifier = Modifier.width(32.dp),
                    )
                    TextButton(
                        onClick = { onChange(item.copy(quantity = (item.quantity + 1).coerceAtMost(99))) },
                        enabled = item.quantity < 99,
                    ) { Text("+", fontSize = 20.sp) }
                }
            }

            SettingsSection(
                footer = if (item.tagPath.isEmpty()) {
                    "Filed automatically from the description. Pick a category to overrule " +
                        "that for this line."
                } else {
                    "You picked this category, so the description won't change it."
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickingCategory = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Category", modifier = Modifier.weight(1f), color = bbInk)
                    Text(
                        if (item.tagPath.isEmpty()) {
                            item.parsedCategory?.let { "$it (automatic)" } ?: "Automatic"
                        } else {
                            tags.firstOrNull { it.path == item.tagPath }?.display ?: item.tagPath
                        },
                        color = bbInkSecondary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = bbInkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The tag vocabulary in force, as a list to pick from.
 *
 * Over `tags()` rather than `categories()` on purpose: `categories()` is only the
 * paths that map to an account, and several of the bundled tags deliberately map
 * to none — `grocery/dairy/milk` and `grocery/meat/chicken` among them — while
 * still being the honest name for a line. Core walks a picked path to its nearest
 * mapped ancestor for exactly this reason, so offering only the mapped ones would
 * hide the specific labels for no gain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerScreen(
    selection: String,
    tags: List<ItemTag>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)

    // Matched on both halves: the display name is what someone reads, and the
    // path is what distinguishes two tags that share one ("Chicken" under meat,
    // and a chicken under deli).
    val needle = query.trim().lowercase()
    val matches = if (needle.isEmpty()) tags else tags.filter {
        it.display.lowercase().contains(needle) || it.path.lowercase().contains(needle)
    }

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                title = { Text("Category") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bbCanvas),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a category") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                TagRow(
                    title = "Automatic",
                    subtitle = "File it from the description",
                    selected = selection.isEmpty(),
                    onClick = { onSelect("") },
                )
                matches.forEach { tag ->
                    BbHairline(startInset = 0.dp)
                    TagRow(
                        title = tag.display,
                        subtitle = tag.path,
                        selected = selection == tag.path,
                        onClick = { onSelect(tag.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bbCanvas)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = bbInk)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = bbInkSecondary)
        }
        if (selected) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BbAccent)
        }
    }
}
