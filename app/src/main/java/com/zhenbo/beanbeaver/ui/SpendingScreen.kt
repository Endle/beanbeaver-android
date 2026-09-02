package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.unexported
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.bbCanvas
import com.zhenbo.beanbeaver.ui.theme.bbInk
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary
import com.zhenbo.beanbeaver.ui.theme.bbInkTertiary
import com.zhenbo.beanbeaver.ui.theme.bbUnexported

/**
 * Where a month's money went, computed from scanned receipts' *items* rather than
 * their totals (see [SpendSummary]). Kotlin twin of iOS `SpendingView`.
 *
 * A spend tracker first: the headline is everything tracked, and the breakdown is
 * every category the classifier reached, largest first.
 *
 * The monthly budget that used to overlay one category is **gone** — target bar,
 * pace line, the "Set a Monthly Budget" row and its editor dialog. A target
 * answers "am I allowed to spend this?", and the product's question is now "what
 * am I spending, and is it climbing?", which the week-over-week card answers
 * instead.
 *
 * The **per-leaf progress bars are gone too**: a dozen neutral capsules, each
 * measured against the largest leaf anywhere in the month, answering a comparison
 * nobody makes and costing every row a second line. A share percentage in a
 * fixed-width column replaced them.
 *
 * Read-only over receipts: nothing here edits one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(
    /**
     * Opens the scanner — the empty state's action, since a spending screen
     * reached with nothing scanned has nothing else useful to offer.
     */
    onScan: () -> Unit,
    onOpenReceipts: (monthFilter: String?) -> Unit,
    onOpenCategory: (category: SpendSummary.Category, title: String, monthId: String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val records by SpendStore.records.collectAsStateWithLifecycle()
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()

    var selectedMonthId by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * Which category the week-over-week chart is trending, and which roots have
     * had their leaf tail expanded. Both view-local and reset on entry, by
     * design: they are ways of looking at the month, not preferences.
     */
    var trendScope by remember { mutableStateOf<SpendSummary.Category?>(null) }
    var expandedRoots by remember { mutableStateOf(emptySet<String>()) }
    var showReconciliation by rememberSaveable { mutableStateOf(false) }

    val monthIds = remember(records) { SpendSummary.monthIds(records) }
    val activeMonthId = selectedMonthId ?: SpendSummary.defaultMonthId(records)
    val summary = remember(records, activeMonthId) { SpendSummary.month(activeMonthId, records) }
    val isCurrentMonth = activeMonthId == SpendSummary.currentMonthId()

    Scaffold(
        containerColor = bbCanvas,
        topBar = {
            TopAppBar(
                // The screen's name, not the month it happens to be showing — the
                // stepper below says which month, and it can page away from this one.
                title = { Text("Spending") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bbCanvas),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        // The same control Home's slip carries, over the same
                        // single piece of state the Settings toggle writes.
                        AmountPrivacyEye()
                        IconButton(onClick = { onOpenReceipts(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "All receipts")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptySpending(modifier = Modifier.fillMaxSize().padding(padding), onScan = onScan)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderSlip(
                summary = summary,
                monthIds = monthIds,
                activeMonthId = activeMonthId,
                records = records,
                hidden = hidden,
                onSelectMonth = { selectedMonthId = it },
                onOpenReceipts = { onOpenReceipts(activeMonthId) },
            )

            // Only for the month in progress. The series is six weeks back from
            // *today*, so beside a March total viewed in August it would be
            // answering a question nobody asked.
            if (SpendSummary.SHOW_WEEKLY_TREND && isCurrentMonth) {
                WeekOverWeekCard(
                    records = records,
                    summary = summary,
                    scope = trendScope,
                    hidden = hidden,
                    onSelectScope = { trendScope = it },
                )
            }

            summary.roots.forEach { group ->
                RootCard(
                    group = group,
                    monthTotal = summary.tracked,
                    hidden = hidden,
                    expanded = expandedRoots.contains(group.id),
                    onExpand = { expandedRoots = expandedRoots + group.id },
                    onOpenRoot = {
                        onOpenCategory(SpendSummary.Category.Root(group.id), group.label, activeMonthId)
                    },
                    onOpenLeaf = { leaf ->
                        onOpenCategory(SpendSummary.Category.Leaf(leaf.label), leaf.label, activeMonthId)
                    },
                )
            }

            ReconciliationCard(
                summary = summary,
                hidden = hidden,
                expanded = showReconciliation,
                onToggle = { showReconciliation = !showReconciliation },
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptySpending(modifier: Modifier = Modifier, onScan: () -> Unit) {
    Column(
        modifier = modifier.background(bbCanvas).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing Tracked Yet", style = MaterialTheme.typography.titleMedium, color = bbInk)
        Text(
            "Scan a receipt and its items show up here, sorted into categories.",
            style = MaterialTheme.typography.bodyMedium,
            color = bbInkSecondary,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onScan) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan a Receipt", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The month's total, the window it covers, and the way through to the receipts
 * behind it.
 *
 * Same construction as Home's slip, and deliberately so: two screens whose
 * headers are built differently read as two apps. Centred here rather than
 * leading-aligned because this screen's job is the single figure and the stepper
 * flanking it, while Home's slip has an eye in the corner to hang a left edge on.
 *
 * The figure is ink, not accent: red on a 44sp money total reads as an alarm, and
 * a month's spending is not an alarm. Accent is kept for things that can be
 * tapped and for the trend delta, which is the one figure here that genuinely is
 * a signal.
 */
@Composable
private fun HeaderSlip(
    summary: SpendSummary.Month,
    monthIds: List<String>,
    activeMonthId: String,
    records: List<SpendRecord>,
    hidden: Boolean,
    onSelectMonth: (String) -> Unit,
    onOpenReceipts: () -> Unit,
) {
    val index = monthIds.indexOf(activeMonthId)
    // `monthIds` is newest-first, so "older" moves to a higher index and "newer"
    // moves to a lower one.
    val canGoOlder = index >= 0 && index + 1 in monthIds.indices
    val canGoNewer = index >= 0 && index - 1 in monthIds.indices

    ReceiptSlip {
        // The month stepper, on the slip's eyebrow line. **This is what replaced
        // "tracked spend".** That subhead named the metric, which is the one
        // thing a spending screen doesn't need to say. What a reader actually
        // can't tell is *which* month and *how many receipts* are behind the
        // figure, so the line says that instead, and carries the paging with it
        // rather than spending another row on a control.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepperArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                description = "Older month",
                enabled = canGoOlder,
                onClick = { if (canGoOlder) onSelectMonth(monthIds[index + 1]) },
            )
            BbEyebrow(
                "${summary.label} · ${summary.receiptCount} " +
                    "receipt${if (summary.receiptCount == 1) "" else "s"}",
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            StepperArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                description = "Newer month",
                enabled = canGoNewer,
                onClick = { if (canGoNewer) onSelectMonth(monthIds[index - 1]) },
            )
        }

        Spacer(Modifier.height(12.dp))

        DisplayAmount(
            amount = summary.tracked,
            hidden = hidden,
            size = 44.sp,
            tracking = (-1.5).sp,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        MetaLine(
            summary = summary,
            activeMonthId = activeMonthId,
            records = records,
            hidden = hidden,
            onOpenReceipts = onOpenReceipts,
        )
    }
}

@Composable
private fun StepperArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = description,
            // Tertiary is a non-text token, and a chevron is not text.
            tint = if (enabled) bbInkSecondary else bbInkTertiary,
        )
    }
}

/**
 * One line under the total: what it averages per day, and the month's backlog if
 * it has one.
 *
 * The backlog half is the tap target rather than inert text — "13 not exported"
 * is the most natural thing to reach for when you want to see which ones, and it
 * is the only place on this screen that says so.
 *
 * Worded "not exported", as every other surface that names this state words it —
 * the row dots, the detail card, the Receipts chip this link leads to. This
 * screen and that chip both used to say "unfiled", which read as a second concept
 * rather than the same one when the link put them one tap apart.
 */
@Composable
private fun MetaLine(
    summary: SpendSummary.Month,
    activeMonthId: String,
    records: List<SpendRecord>,
    hidden: Boolean,
    onOpenReceipts: () -> Unit,
) {
    val facts = remember(records, activeMonthId) { SpendSummary.facts(activeMonthId, records) }
    // This month's un-exported receipts — the same `isExported` split the
    // Receipts screen's dots and chips draw, scoped to the month on screen.
    val backlog = summary.records.unexported.size

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${maskedAmount(formatCurrency(facts.dailyAverage), hidden)}/day over " +
                "${facts.days} day${if (facts.days == 1u) "" else "s"}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = bbInkSecondary,
            maxLines = 1,
        )
        if (backlog > 0) {
            Box(
                Modifier
                    .padding(horizontal = 8.dp)
                    .size(width = 1.dp, height = 11.dp)
                    .background(bbInk.copy(alpha = 0.2f)),
            )
            Row(
                modifier = Modifier.clickable(onClick = onOpenReceipts),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ExportStatusDot(SpendRecord.ExportStatus.NOT_EXPORTED, size = 7.dp)
                Text(
                    "$backlog not exported",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = bbUnexported,
                    maxLines = 1,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = bbUnexported,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * Six weeks of spending, scoped to whichever chip is selected — the card that
 * replaced "Set a Monthly Budget".
 *
 * Scoping is the point rather than a refinement: "am I spending more?" is a
 * different question for meat than for the total, and a household run that lands
 * in one week hides a grocery trend inside an all-categories line. The delta, the
 * mean and the caption all re-scope with it.
 */
@Composable
private fun WeekOverWeekCard(
    records: List<SpendRecord>,
    summary: SpendSummary.Month,
    scope: SpendSummary.Category?,
    hidden: Boolean,
    onSelectScope: (SpendSummary.Category?) -> Unit,
) {
    val trend = remember(records, scope) { SpendSummary.trend(scope, records) }
    // Every scope the chips offer: all spending, then each root with its own
    // leaves under it. Derived from the month on screen rather than a fixed list,
    // so a category that only appears once you scan a hardware store appears here
    // the same day.
    val scopes = remember(summary) {
        buildList<Pair<String, SpendSummary.Category?>> {
            add("All spending" to null)
            summary.roots.forEach { root ->
                add(root.label to SpendSummary.Category.Root(root.id))
                root.leaves.forEach { leaf ->
                    add(leaf.label to SpendSummary.Category.Leaf(leaf.label))
                }
            }
        }
    }

    BbCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BbEyebrow("Week over week")
            Spacer(Modifier.weight(1f))
            TrendDeltaLabel(trend = trend, hidden = hidden)
        }

        Spacer(Modifier.height(12.dp))

        // Mirrors the Receipts screen's chip row — same metrics, same scroll
        // behaviour — so the two read as one control the app uses twice.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scopes.forEach { (label, value) ->
                ScopeChip(
                    label = label,
                    selected = value == scope,
                    onClick = { onSelectScope(value) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Bars, matching Home. A line here and bars there would be two shapes for
        // one series, and the argument for bars is the same on both screens.
        if (hidden) {
            TrendChartMasked(height = 72.dp)
        } else {
            TrendBars(amounts = trend.amounts, labels = trend.weekLabels, height = 72.dp)
            Spacer(Modifier.height(8.dp))
            // The mean, as a caption rather than a rule across the bars: on a
            // line it was a reference to read against; over bars it is one more
            // horizontal edge competing with six of them.
            Text(
                "avg ${maskedAmount(formatCurrency(trend.mean), hidden)}/wk",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = bbInkSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Pick any category to trend on its own — Meat alone, or Dairy, not just the total.",
            fontSize = 12.sp,
            color = bbInkSecondary,
        )
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = if (selected) androidx.compose.ui.graphics.Color.White else bbInk,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) BbAccent else bbInk.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/**
 * How many leaves a card shows before collapsing the rest.
 *
 * Six is the design's own card. It is enough that the long tail of a real grocery
 * month — a dozen leaves, several of them under a dollar — doesn't turn one root
 * into a screenful, and few enough that the tail control is visible without
 * scrolling the card off.
 */
private const val VISIBLE_LEAVES = 6

/** One top-level category: its total, then the leaves beneath it. */
@Composable
private fun RootCard(
    group: SpendSummary.RootGroup,
    monthTotal: Double,
    hidden: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpenRoot: () -> Unit,
    onOpenLeaf: (SpendSummary.Leaf) -> Unit,
) {
    val shown = if (expanded) group.leaves else group.leaves.take(VISIBLE_LEAVES)
    val hiddenLeaves = group.leaves.drop(shown.size)

    BbCard(padding = 0.dp) {
        RootRow(group = group, monthTotal = monthTotal, hidden = hidden, onClick = onOpenRoot)
        shown.forEach { leaf ->
            BbHairline()
            LeafRow(
                leaf = leaf,
                rootTotal = group.amount,
                hidden = hidden,
                onClick = { onOpenLeaf(leaf) },
            )
        }
        if (hiddenLeaves.isNotEmpty()) {
            TailRow(
                count = hiddenLeaves.size,
                amount = hiddenLeaves.sumOf { it.amount },
                hidden = hidden,
                onExpand = onExpand,
            )
        }
    }
}

/** The card's own header: icon tile, name, total, share of the month, chevron. */
@Composable
private fun RootRow(
    group: SpendSummary.RootGroup,
    monthTotal: Double,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Padding before the hit region, not after: the text band alone is
            // well under the 48dp touch minimum.
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A tinted square rather than a bare glyph: it gives the root rows one
        // shared left edge down the screen, which is what makes a stack of cards
        // read as one list of categories.
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(BbAccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                group.label.take(1).uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BbAccent,
            )
        }
        Text(
            group.label,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = bbInk,
            modifier = Modifier.weight(1f),
        )
        Text(
            maskedAmount(formatCurrency(group.amount), hidden),
            fontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = bbInk,
        )
        SharePercent(group.amount, monthTotal, hidden)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = bbInkTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A leaf. **Chevron on every row**, so the row reads as the way into its items
 * rather than as a line in a table that happens to be tappable.
 */
@Composable
private fun LeafRow(
    leaf: SpendSummary.Leaf,
    rootTotal: Double,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(leaf.label, fontSize = 16.sp, color = bbInk, modifier = Modifier.weight(1f))
        Text(
            maskedAmount(formatCurrency(leaf.amount), hidden),
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = bbInk,
        )
        // Of its own root, not of the month: "Milk is 31% of Grocery" is the
        // comparison the row sits inside. A share of the month would make every
        // leaf a small number and say nothing about the card.
        SharePercent(leaf.amount, rootTotal, hidden)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = bbInkTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The share column: fixed width and right-aligned, so the percentages line up
 * down the card whether they are one digit or three.
 *
 * Blank while masked. A percentage is not a dollar figure, but `4%` of a hidden
 * total next to `62%` of it still describes the month, and the point of the eye
 * is that a glance over your shoulder learns nothing.
 */
@Composable
private fun SharePercent(amount: Double, total: Double, hidden: Boolean) {
    Text(
        if (hidden || total <= 0) "" else "${Math.round(amount / total * 100)}%",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = bbInkSecondary,
        textAlign = TextAlign.End,
        modifier = Modifier.width(30.dp),
    )
}

/**
 * The collapsed tail, as a **control rather than a caption**.
 *
 * The grey "10 more items · $203.05" line this replaces read as a footnote, and
 * footnotes don't get tapped. Accent label, the hidden sum beside it, and a
 * chevron — the same treatment the scan result's "Show all 14 items" uses, so one
 * pattern covers both places the app collapses a list.
 */
@Composable
private fun TailRow(count: Int, amount: Double, hidden: Boolean, onExpand: () -> Unit) {
    // Full-bleed, unlike the row dividers above: it separates the list from a
    // control, not one row from the next.
    BbHairline(startInset = 0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Show $count more",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = BbAccent,
            modifier = Modifier.weight(1f),
        )
        Text(
            maskedAmount(formatCurrency(amount), hidden),
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = bbInkSecondary,
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = BbAccent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * How the headline relates to what was actually printed on the receipts. Stated
 * rather than hidden: `items + tax` should land on `receiptTotal`, and when it
 * doesn't, the gap gets its own named row and a sentence saying what it usually
 * is — a scan that missed a discount line will otherwise look like arithmetic the
 * app got wrong.
 *
 * Collapsed to one line by default. That line states the same three figures the
 * full breakdown leads with, so opening it is a request for the *rest* rather
 * than the only way to learn there is a gap at all.
 */
@Composable
private fun ReconciliationCard(
    summary: SpendSummary.Month,
    hidden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val parts = buildList {
        add("Items ${maskedAmount(formatCurrency(summary.itemsTotal), hidden)}")
        if (summary.tax > 0) add("tax ${maskedAmount(formatCurrency(summary.tax), hidden)}")
        summary.unaccounted?.let {
            add("${maskedAmount(formatCurrency(it), hidden)} unaccounted")
        }
    }

    BbCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = bbInkSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = bbInkTertiary,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                BbHairline(startInset = 0.dp)
                Spacer(Modifier.height(6.dp))
                FooterRow("Items", maskedAmount(formatCurrency(summary.itemsTotal), hidden))
                if (summary.tax > 0) {
                    FooterRow("Tax", maskedAmount(formatCurrency(summary.tax), hidden))
                }
                FooterRow(
                    "Receipt total",
                    maskedAmount(formatCurrency(summary.receiptTotal), hidden),
                )
                summary.unaccounted?.let { gap ->
                    FooterRow("Unaccounted", maskedAmount(formatCurrency(gap), hidden))
                    Text(
                        "Items and tax don't add up to what the receipts say — usually a " +
                            "discount or a line the scan didn't read.",
                        style = MaterialTheme.typography.labelSmall,
                        color = bbInkSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (summary.excludedCount > 0) {
                    val n = summary.excludedCount
                    FooterRow("Excluded", "$n receipt${if (n == 1) "" else "s"}")
                }
                if (summary.unreadablePriceCount > 0) {
                    FooterRow("Unreadable prices", "${summary.unreadablePriceCount}")
                }
            }
        }
    }
}

@Composable
private fun FooterRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = bbInkSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = bbInkSecondary,
            fontFamily = FontFamily.Monospace,
        )
    }
}
