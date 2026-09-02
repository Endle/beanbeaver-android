package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.zhenbo.beanbeaver.receipt.PhotoSaver
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.needsAttention
import com.zhenbo.beanbeaver.receipt.unexported
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.cardBackground
import com.zhenbo.beanbeaver.ui.theme.bbCanvas
import com.zhenbo.beanbeaver.ui.theme.bbInk

/**
 * Every scanned receipt — the "see everything I scanned" and "bulk backup" halves
 * of the feature. Kotlin twin of iOS `ReceiptsView`.
 *
 * Unlike a batch, this list never drains on export: a receipt lives here until the
 * user deletes it or clears its photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    /**
     * When set (from the spending screen), only that month's receipts are shown
     * and the title reflects it. Null shows everything, newest first.
     */
    monthFilter: String?,
    onOpenReceipt: (SpendRecord) -> Unit,
    onExport: (List<SpendRecord>) -> Unit,
    exportReady: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    val allRecords by SpendStore.records.collectAsStateWithLifecycle()
    // The same single state the home slip's eye and the Settings toggle write,
    // so the row subtitles mask with everything else.
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()

    /**
     * Everything in scope, before the chips narrow it — what the chip counts are
     * computed over, so "Not exported 3" always agrees with what tapping it shows.
     */
    val scopedRecords = remember(allRecords, monthFilter) {
        if (monthFilter == null) allRecords
        else allRecords.filter { SpendSummary.monthId(it) == monthFilter }
    }

    /**
     * Months present in the list, newest first, each with its receipt count.
     * Empty when the caller already narrowed to one month — a month chip row
     * would be one chip that changes nothing.
     */
    val monthChips = remember(scopedRecords, monthFilter) {
        if (monthFilter != null) emptyList() else {
            val thisYear = SpendSummary.currentMonthId().take(4)
            SpendSummary.monthIds(scopedRecords).map { id ->
                // "March", not "March 2026" — a chip is a word wide, and the year
                // only earns its space once the list reaches back past this one.
                val full = SpendSummary.monthLabel(id)
                MonthChip(
                    id = id,
                    label = if (id.startsWith(thisYear)) full.substringBefore(' ') else full,
                    count = scopedRecords.count { SpendSummary.monthId(it) == id },
                )
            }
        }
    }

    /**
     * Merchants worth a chip: the recurring ones, busiest first. A merchant seen
     * once is a row in the list, not a way to narrow it.
     */
    val merchantChips = remember(scopedRecords) {
        scopedRecords.groupBy { it.result.merchant }
            .map { MerchantChip(it.key, it.value.size) }
            .filter { it.count > 1 }
            .sortedWith(compareByDescending<MerchantChip> { it.count }.thenBy { it.name })
            .take(4)
    }

    // Not persisted: the filter is a question you ask on the way to doing
    // something ("what haven't I filed?"), not a preference — and one that
    // survived a relaunch would hide receipts from someone who'd forgotten they
    // set it. `rememberSaveable` still carries it across a rotation.
    //
    // Null means "whatever the default says" — the newest month, which is what
    // the list should open on and which isn't knowable at init. Opening on
    // everything-ever is the wrong first answer once there is more than a month
    // of it, and every receipt stays one chip away.
    var filter by rememberSaveable { mutableStateOf<ReceiptFilter?>(null) }
    val activeFilter = filter
        ?: monthChips.firstOrNull()?.let { ReceiptFilter.Month(it.id) }
        ?: ReceiptFilter.All
    val records = scopedRecords.filter(activeFilter::matches)

    /**
     * The leading category across the receipts on screen, and what each receipt
     * spent in it.
     *
     * `roots.first` follows the same order the Spending screen draws, so this row
     * and that screen agree on what leads. One FFI call for the whole list rather
     * than a rollup per row.
     */
    val categoryShare = remember(scopedRecords) {
        val month = SpendSummary.month(SpendSummary.defaultMonthId(scopedRecords), scopedRecords)
        month.roots.firstOrNull()?.let { root ->
            val ids = scopedRecords.map { it.id }.toSet()
            root.label.lowercase() to SpendSummary
                .receipts(SpendSummary.Category.Root(root.id), scopedRecords)
                .filter { it.record.id in ids }
                .associate { it.record.id to it.amount }
        }
    }

    /**
     * The backlog the footer bar acts on — scoped to the month being shown, but
     * deliberately *not* to the chips: the bar means "file everything here that
     * isn't filed", and that shouldn't change meaning when the view is narrowed
     * to the exported slice.
     */
    val backlog = scopedRecords.unexported

    var editing by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var confirmClearAllPhotos by rememberSaveable { mutableStateOf(false) }

    val selected = records.filter { it.id in selection }

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                title = { Text(monthFilter?.let(SpendSummary::monthLabel) ?: "Receipts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (scopedRecords.isEmpty()) return@TopAppBar
                    TextButton(onClick = {
                        editing = !editing
                        if (!editing) selection = emptySet()
                    }) { Text(if (editing) "Done" else "Select") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Select Unexported") },
                                onClick = {
                                    menuOpen = false
                                    selection = records.filterNot { it.isExported }.map { it.id }.toSet()
                                    editing = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All Photos") },
                                onClick = { menuOpen = false; confirmClearAllPhotos = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete All Receipts") },
                                onClick = { menuOpen = false; confirmDeleteAll = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (scopedRecords.isEmpty()) {
            EmptyState(
                title = "No Receipts",
                message = if (monthFilter == null) "Scanned receipts show up here."
                else "No receipts scanned this month.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Hidden while selecting: the chips would fight the selection for
            // what a tap means, and narrowing the list under a live selection
            // silently changes what "Export 4 Receipts" is about to send.
            if (!editing) {
                FilterChips(
                    filter = activeFilter,
                    onSelect = { filter = it },
                    total = scopedRecords.size,
                    backlogCount = backlog.size,
                    monthChips = monthChips,
                    merchantChips = merchantChips,
                )
            }

            if (records.isEmpty()) {
                // Only `Not exported` can empty the list now — a month or a
                // merchant chip only exists because it has receipts in it.
                EmptyState(
                    title = "Nothing to Export",
                    message = "Every receipt here has reached your ledger.",
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(records, key = { it.id }) { record ->
                        ReceiptRow(
                            record = record,
                            detail = detail(context, record, categoryShare, hidden),
                            editing = editing,
                            checked = record.id in selection,
                            onToggle = {
                                selection = if (record.id in selection) selection - record.id
                                else selection + record.id
                            },
                            onOpen = { onOpenReceipt(record) },
                            onToggleExcluded = {
                                SpendStore.setExcluded(context, record.id, !record.isExcluded)
                            },
                            onDelete = { SpendStore.remove(context, record.id) },
                        )
                    }
                }
            }

            if (editing) {
                EditFooter(
                    selectedCount = selected.size,
                    exportReady = exportReady,
                    onDelete = { confirmDeleteSelected = true },
                    onExport = { onExport(selected) },
                )
            } else if (backlog.isNotEmpty()) {
                BacklogFooter(
                    count = backlog.size,
                    exportReady = exportReady,
                    onExport = { onExport(backlog) },
                )
            }
        }
    }

    if (confirmClearAllPhotos) {
        ConfirmDialog(
            title = "Clear all photos?",
            message = "Frees the space used by every receipt photo. Every receipt's parsed data " +
                "and every spend figure stay exactly as they are.",
            confirmLabel = "Clear Photos",
            onConfirm = { SpendStore.clearAllPhotos(context); confirmClearAllPhotos = false },
            onDismiss = { confirmClearAllPhotos = false },
        )
    }
    if (confirmDeleteSelected) {
        val n = selected.size
        ConfirmDialog(
            title = if (n == 1) "Delete this receipt?" else "Delete $n receipts?",
            message = "Removes the parsed data and the photos for the receipts you selected. " +
                "Everything else stays. Anything already exported to your ledger is untouched, " +
                "and originals stay in your photo library.",
            confirmLabel = "Delete $n Receipt${if (n == 1) "" else "s"}",
            onConfirm = {
                SpendStore.remove(context, selection)
                selection = emptySet()
                confirmDeleteSelected = false
                // Leave the user somewhere sensible: the toolbar is hidden for an
                // empty list, so staying in edit mode would take "Done" with it
                // and strand the screen.
                if (scopedRecords.size == n) editing = false
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
    if (confirmDeleteAll) {
        val n = allRecords.size
        ConfirmDialog(
            title = "Delete all receipts?",
            message = "Removes the parsed data and the photos for every scanned receipt on this " +
                "device. Anything already exported to your ledger is untouched, and originals " +
                "stay in your photo library.",
            confirmLabel = "Delete $n Receipt${if (n == 1) "" else "s"}",
            onConfirm = {
                SpendStore.removeAll(context)
                selection = emptySet()
                editing = false
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/**
 * Which slice the list is showing.
 *
 * **Time and place lead now, and ledger state is one chip of several.** The row
 * used to be `All / Not exported / Exported`, which organised browsing entirely
 * around export — a chore, not a reason to open the list. `Exported` is gone: it
 * answered the inverse of a question nobody asks, and every receipt it held is
 * reachable through its month.
 */
sealed interface ReceiptFilter {
    data object All : ReceiptFilter
    data class Month(val id: String) : ReceiptFilter
    data object NotExported : ReceiptFilter
    data class Merchant(val name: String) : ReceiptFilter

    fun matches(record: SpendRecord): Boolean = when (this) {
        All -> true
        is Month -> SpendSummary.monthId(record) == id
        NotExported -> !record.isExported
        is Merchant -> record.result.merchant == name
    }
}

/**
 * Time and place first, with the one retained export filter second.
 *
 * `Not exported` sits in position three deliberately: it is the chip with an
 * action behind it, and last in a scrolling row is where a chip gets clipped and
 * goes unseen. It is worded exactly as the row dots and Spending's meta line word
 * it — one state, one phrase, wherever it is named.
 *
 * **`All` leads, and is shown even when nothing is scoped** — it is the way back
 * out of every other chip. The unscoped list opens on the newest month, so
 * without it there is no chip that means "stop narrowing", and the older receipts
 * a month chip hides are unreachable rather than one tap away.
 */
@Composable
private fun FilterChips(
    filter: ReceiptFilter,
    onSelect: (ReceiptFilter) -> Unit,
    total: Int,
    backlogCount: Int,
    monthChips: List<MonthChip>,
    merchantChips: List<MerchantChip>,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bbCanvas)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(ReceiptFilter.All, filter, total, null, "All", onSelect)
            monthChips.firstOrNull()?.let {
                FilterChip(ReceiptFilter.Month(it.id), filter, it.count, null, it.label, onSelect)
            }
            FilterChip(
                ReceiptFilter.NotExported, filter, backlogCount,
                SpendRecord.ExportStatus.NOT_EXPORTED, "Not exported", onSelect,
            )
            monthChips.drop(1).forEach {
                FilterChip(ReceiptFilter.Month(it.id), filter, it.count, null, it.label, onSelect)
            }
            merchantChips.forEach {
                // Title-cased to match the rows — the chip and the receipts it
                // selects have to be the same word, and the parse carries the
                // merchant as printed (`COSTCO`).
                FilterChip(
                    ReceiptFilter.Merchant(it.name), filter, it.count, null,
                    titleCase(it.name), onSelect,
                )
            }
        }
        // The canvas rather than a raised surface: the row is part of the page,
        // not a toolbar over it. The hairline is what separates it from the list.
        BbHairline(startInset = 0.dp)
    }
}

/** A month present in the list, with how many receipts it holds. */
data class MonthChip(val id: String, val label: String, val count: Int)

/** A merchant worth a chip: seen more than once. */
data class MerchantChip(val name: String, val count: Int)

@Composable
private fun FilterChip(
    value: ReceiptFilter,
    current: ReceiptFilter,
    count: Int,
    status: SpendRecord.ExportStatus?,
    label: String,
    onSelect: (ReceiptFilter) -> Unit,
) {
    val selected = value == current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) BbAccent else bbInk.copy(alpha = 0.07f))
            .selectable(selected = selected, onClick = { onSelect(value) })
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        // The selected chip is a solid accent fill, so a coloured dot on it
        // would be unreadable — and redundant, since the chip is already the
        // loudest thing in the row.
        if (status != null && !selected) {
            ExportStatusDot(status, size = 8.dp)
        }
        Text(
            "$label $count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else bbInk,
        )
    }
}

/**
 * One tap to export the whole backlog. The bar is present whenever there's a
 * backlog and absent the moment there isn't, so it doubles as the answer to "am
 * I up to date?" — a screen with no bar is a screen with nothing owing. It used
 * to take four taps through a menu most people never opened (Select → ⋮ →
 * Select Unexported → Export).
 */
@Composable
private fun BacklogFooter(count: Int, exportReady: Boolean, onExport: () -> Unit) {
    Surface(color = cardBackground) {
        Button(
            onClick = onExport,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                when {
                    !exportReady -> "Set Up Export…"
                    count == 1 -> "Export 1 Receipt"
                    else -> "Export $count Receipts"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Facts about the row that aren't its export status — everything the dot doesn't
 * already say. Which target a receipt reached moved to the detail screen with
 * the dot; what's left is the photo and the budget exclusion, which is a fact
 * about the row rather than a state of its export (see
 * [SpendRecord.ExportStatus] for why the dot deliberately doesn't carry it).
 *
 * Lowercased: these join the date/item-count subtitle now rather than heading
 * their own line.
 */
private fun detail(
    context: android.content.Context,
    record: SpendRecord,
    categoryShare: Pair<String, Map<String, Double>>?,
    hidden: Boolean,
): String? = buildList {
    // The share is simply omitted for a receipt with none of the leading
    // category, rather than printing a zero.
    categoryShare?.let { (label, byRecord) ->
        val amount = byRecord[record.id]
        if (amount != null && amount > 0) {
            add("${maskedAmount(formatCurrency(amount), hidden)} $label")
        }
    }
    when (SpendStore.photoState(context, record)) {
        SpendRecord.PhotoState.PRESENT -> Unit
        SpendRecord.PhotoState.CLEARED -> add("photo cleared")
        SpendRecord.PhotoState.UNAVAILABLE -> add("photo unavailable")
    }
    if (record.isExcluded) add("excluded from totals")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun ReceiptRow(
    record: SpendRecord,
    detail: String?,
    editing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onToggleExcluded: () -> Unit,
    onDelete: () -> Unit,
) {
    var rowMenuOpen by remember { mutableStateOf(false) }
    val result = record.result

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (editing) onToggle() else onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            // Export state as a glyph, not a caption. It used to be a grey line
            // in the same weight and colour as "Photo cleared", so nothing
            // distinguished a not-yet-exported receipt at a glance.
            ExportStatusDot(record.exportStatus)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleCase(result.merchant),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (result.needsAttention) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Needs a look",
                            tint = BbAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // `detail` joins the subtitle rather than sitting on its own
                // line. Photo state used to be a third line, in the same weight
                // and colour as the export caption above it — which made a fact
                // about the row ("photo cleared") look exactly like its status.
                // The dot is status now; anything on this line is not.
                val subtitle = buildList {
                    friendlyDate(result.date)?.let { add(it) }
                    val count = result.items.size
                    if (count > 0) add("$count item${if (count == 1) "" else "s"}")
                    if (!detail.isNullOrEmpty()) add(detail)
                }.joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatPrice(result.total).text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            if (!editing) {
                // Compose has no swipe actions the way a SwiftUI List does, so the
                // per-row destructive/exclude gestures live in an overflow menu.
                Box {
                    IconButton(onClick = { rowMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Actions for ${titleCase(result.merchant)}",
                        )
                    }
                    DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (record.isExcluded) "Include in spend" else "Exclude from spend") },
                            onClick = { rowMenuOpen = false; onToggleExcluded() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { rowMenuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Both things a selection can be used for, side by side. Deleting a chosen few
 * used to have no home here: the row menu does one and the overflow does all of
 * them, so trimming a dozen receipts meant a dozen taps. Selection already existed
 * for export — this just lets the same selection be thrown away, which is also why
 * "Select Unexported" now composes into "delete what I never filed".
 */
@Composable
private fun EditFooter(
    selectedCount: Int,
    exportReady: Boolean,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = cardBackground) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (selectedCount == 1) "Delete 1 selected receipt"
                    else "Delete $selectedCount selected receipts",
                    tint = BbAccent,
                )
            }
            Button(
                onClick = onExport,
                enabled = selectedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        !exportReady -> "Set Up Export…"
                        selectedCount == 1 -> "Export 1 Receipt"
                        else -> "Export $selectedCount Receipts"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * One recorded receipt in full, reached from the Receipts list or from a category
 * drill-down. The batch's detail screen can't be reused as-is: its destructive
 * action is "remove from batch", while here the photo and the record have separate
 * lifetimes — clearing the photo keeps every spend figure the record contributes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordedReceiptScreen(record: SpendRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPhoto by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var confirmClearPhoto by rememberSaveable { mutableStateOf(false) }
    var photoMenuOpen by remember { mutableStateOf(false) }

    // Outcome of the last "Save to Camera Roll". One piece of state for both
    // outcomes: the action is invisible either way once the menu closes, so
    // success needs saying as much as failure does.
    var saveOutcome by remember { mutableStateOf<SaveOutcome?>(null) }

    // Re-read from the store rather than trusting the record handed in, so
    // clearing the photo updates this screen instead of only the list behind it.
    val records by SpendStore.records.collectAsStateWithLifecycle()
    val current = records.firstOrNull { it.id == record.id } ?: record
    val photoFile = SpendStore.photoFile(context, current)

    val photoBytes by androidx.compose.runtime.produceState<ByteArray?>(null, photoFile?.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            photoFile?.let { f -> runCatching { f.readBytes() }.getOrNull() }
        }
    }

    if (showPhoto) {
        photoBytes?.let {
            OriginReceiptScreen(imageData = it, onBack = { showPhoto = false })
            return
        }
    }

    if (showEditor) {
        ReceiptEditorScreen(
            original = current.result,
            imageFile = photoFile,
            exportedAt = current.exportedAt,
            onSave = { SpendStore.updateResult(context, current.id, it) },
            onBack = { showEditor = false },
        )
        return
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                title = { Text(titleCase(current.result.merchant), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showPhoto = true }, enabled = photoBytes != null) {
                        Icon(Icons.Default.Photo, contentDescription = "Show original receipt")
                    }
                    Box {
                        IconButton(onClick = { photoMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = photoMenuOpen,
                            onDismissRequest = { photoMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Review & Fix") },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                onClick = { photoMenuOpen = false; showEditor = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Save to Camera Roll") },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                },
                                enabled = photoFile != null,
                                onClick = {
                                    photoMenuOpen = false
                                    val file = photoFile ?: return@DropdownMenuItem
                                    scope.launch {
                                        saveOutcome = saveToCameraRoll(context, file)
                                    }
                                },
                            )
                        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReceiptCard(result = current.result, wallMs = current.wallMs ?: 0.0)

            ExportStatusCard(current)

            when (SpendStore.photoState(context, current)) {
                SpendRecord.PhotoState.PRESENT -> {
                    OutlinedButton(
                        onClick = { confirmClearPhoto = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Photo", fontWeight = FontWeight.SemiBold)
                    }
                }
                SpendRecord.PhotoState.CLEARED -> PhotoNote("You cleared this receipt's photo.")
                SpendRecord.PhotoState.UNAVAILABLE ->
                    // Worth naming: a re-export of this row can attach no
                    // `document:` link, which is not the user's doing.
                    PhotoNote("This receipt's photo is no longer on this device.")
            }
        }
    }

    if (confirmClearPhoto) {
        ConfirmDialog(
            title = "Clear this photo?",
            message = "Frees the space used by this receipt's photo. Its parsed data and every " +
                "spend figure it contributes to stay exactly as they are.",
            confirmLabel = "Clear Photo",
            onConfirm = { SpendStore.clearPhoto(context, current.id); confirmClearPhoto = false },
            onDismiss = { confirmClearPhoto = false },
        )
    }

    saveOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { saveOutcome = null },
            title = { Text(outcome.title) },
            text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = { saveOutcome = null }) { Text("OK") } },
        )
    }
}

/**
 * Where this receipt has got to, in words. This is where "Filed to GitHub" went
 * when the list row traded it for a dot: the list only has to answer *whether* a
 * receipt is filed; the answer to *where* is worth a line of its own, and this
 * is the one screen with room to say a receipt went to both.
 *
 * Says "Shared to" for Money Manager and "Filed to" for a ledger, matching
 * [SpendStore.markShared]'s honesty about the difference: a share sheet is
 * marked at presentation and may have been cancelled, while a ledger append
 * either landed or reported an error.
 */
@Composable
private fun ExportStatusCard(record: SpendRecord) {
    BbCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExportStatusDot(record.exportStatus)
            Text(
                when {
                    record.exportedAt == null -> "Not exported yet"
                    record.exportedTargets.isEmpty() -> "Exported"
                    else -> record.exportedTargets.joinToString(" · ", transform = ::targetPhrase)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        record.exportedAt?.let { at ->
            Spacer(Modifier.height(6.dp))
            Text(
                "First exported ${friendlyTimestamp(at)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun targetPhrase(target: String): String =
    if (target == "Money Manager") "Shared to Money Manager" else "Filed to $target"

/** What to tell the user after a "Save to Camera Roll" — either way. */
private data class SaveOutcome(val title: String, val message: String)

/**
 * Copy this receipt's photo into the user's photo library. The copy lands
 * outside the app's storage, so it survives Clear Photo and Delete All
 * Receipts — that's the point of the action, and why the confirmation says so
 * rather than just "Saved".
 */
private suspend fun saveToCameraRoll(context: android.content.Context, file: java.io.File): SaveOutcome =
    try {
        PhotoSaver.save(context, file)
        SaveOutcome(
            title = "Saved to Camera Roll",
            message = "A copy of this receipt photo is now in your photo library, under the " +
                "BeanBeaver album. Deleting the receipt here won't remove it.",
        )
    } catch (e: PhotoSaver.Failure) {
        SaveOutcome(title = "Couldn't Save Photo", message = e.message ?: e.toString())
    }

@Composable
private fun PhotoNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
