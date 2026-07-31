package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.ImportedRuleDocument
import com.zhenbo.beanbeaver.receipt.ItemRuleStore
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import uniffi.bb_receipt_ffi.ItemRule
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.RuleBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browse the classification ruleset: which tags exist, which accounts they map
 * to, and which keyword rules produce them. Kotlin twin of iOS `ItemRulesView`.
 *
 * Read-only by design. The rule format may still change, so this deliberately
 * offers no rule editor — the one write gesture is importing a document, which
 * needs no UI for the format itself.
 */
private enum class RulesAxis(val label: String) {
    TAGS("Tags"),
    ACCOUNTS("Accounts"),
    RULES("Rules"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Reading the stored documents and compiling the corpus is off-main: the
    // screen renders empty for one frame and fills in when `book` arrives, which
    // beats blocking the first frame on TOML parsing.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ItemRuleStore.ensureLoaded(context) }
    }

    val book by ItemRuleStore.book.collectAsStateWithLifecycle()
    val documents by ItemRuleStore.documents.collectAsStateWithLifecycle()
    val loadError by ItemRuleStore.loadError.collectAsStateWithLifecycle()

    var axis by rememberSaveable { mutableStateOf(RulesAxis.TAGS) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Sub-screens, as the boolean-gated early returns the rest of the app uses.
    var showExplain by rememberSaveable { mutableStateOf(false) }
    var selectedTagPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRuleIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    // The screens here nest three deep (browser → tag → rule), so system back has
    // to pop that stack. Without this it leaves the app outright, since the "stack"
    // is boolean-gated early returns rather than a real back stack.
    BackHandler(onBack = onBack)

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val toml = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: error("Couldn't open that file.")
            val name = documentName(context, uri) ?: "rules.toml"
            val summary = ItemRuleStore.importDocument(context, name, toml)
            importMessage = "Added ${summary.rules} rule${if (summary.rules == 1) "" else "s"}" +
                if (summary.tags > 0) {
                    " and ${summary.tags} new tag${if (summary.tags == 1) "" else "s"}."
                } else {
                    "."
                }
        } catch (t: Throwable) {
            // The core validated it — surface its message verbatim, since it names
            // the offending tag path or rule id.
            importError = t.message ?: t.toString()
        }
    }

    if (showExplain) {
        ExplainItemScreen(book = book, onBack = { showExplain = false })
        return
    }
    selectedTagPath?.let { path ->
        val tag = book?.tags()?.firstOrNull { it.path == path }
        if (tag != null) {
            TagDetailScreen(
                tag = tag,
                book = book,
                onOpenRule = { selectedTagPath = null; selectedRuleIndex = it },
                onBack = { selectedTagPath = null },
            )
            return
        }
        selectedTagPath = null
    }
    selectedRuleIndex?.let { index ->
        val rule = book?.rules()?.firstOrNull { it.index.toInt() == index }
        if (rule != null) {
            RuleDetailScreen(rule = rule, onBack = { selectedRuleIndex = null })
            return
        }
        selectedRuleIndex = null
    }

    val accountsByPath = remember(book) {
        (book?.categories() ?: emptyList()).associate { it.path to it.account }
    }
    val tags = remember(book, query) { filterTags(book, query) }
    val categories = remember(book, query) { filterCategories(book, query) }
    val rules = remember(book, query) { filterRules(book, query) }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Categories & Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showExplain = true }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Test an item")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Import Rules…") },
                                onClick = {
                                    menuOpen = false
                                    // There is no registered MIME type for TOML, and
                                    // a file picked out of Downloads often arrives as
                                    // application/octet-stream, so anything goes and
                                    // the core rejects what isn't a rule document.
                                    importer.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RulesAxis.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = axis == option,
                            onClick = { axis = option },
                            shape = SegmentedButtonDefaults.itemShape(i, RulesAxis.entries.size),
                        ) { Text(option.label) }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search tags, accounts, keywords") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                loadError?.let { message ->
                    item {
                        SettingsSection(title = "Your rules are not being applied") {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = BbAccent,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                when (axis) {
                    RulesAxis.TAGS -> item {
                        SettingsSection(
                            footer = "A tag is a path, so the same word can sit under two parents. " +
                                "Tags with no account listed only describe the item — the account " +
                                "comes from a parent or from the winning rule.",
                        ) {
                            tags.forEachIndexed { i, tag ->
                                if (i > 0) RowDivider()
                                TagRow(
                                    tag = tag,
                                    account = accountsByPath[tag.path],
                                    onClick = { selectedTagPath = tag.path },
                                )
                            }
                            if (tags.isEmpty()) EmptyForQuery(query)
                        }
                    }

                    RulesAxis.ACCOUNTS -> item {
                        SettingsSection(
                            footer = "Where each tag lands in your ledger. An imported document can " +
                                "remap any of these without touching a rule.",
                        ) {
                            categories.forEachIndexed { i, cat ->
                                if (i > 0) RowDivider()
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(cat.account, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        cat.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (categories.isEmpty()) EmptyForQuery(query)
                        }
                    }

                    RulesAxis.RULES -> item {
                        SettingsSection(
                            footer = "Rules are matched against each item's text. When several match, " +
                                "the highest-priority one supplies the account; every match " +
                                "contributes its tags.",
                        ) {
                            rules.forEachIndexed { i, rule ->
                                if (i > 0) RowDivider()
                                RuleRow(rule) { selectedRuleIndex = rule.index.toInt() }
                            }
                            if (rules.isEmpty()) EmptyForQuery(query)
                        }
                    }
                }

                if (documents.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = "Imported",
                            footer = "Rules from these files layer on top of the built-in ones and " +
                                "apply to every new scan.",
                        ) {
                            documents.forEachIndexed { i, doc ->
                                if (i > 0) RowDivider()
                                ImportedDocumentRow(doc) { ItemRuleStore.remove(context, doc.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            title = { Text("Rules Imported") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importMessage = null }) { Text("OK") } },
        )
    }
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn't Import") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
}

// MARK: - Rows

@Composable
private fun TagRow(tag: ItemTag, account: String?, onClick: () -> Unit) {
    val depth = tag.path.count { it == '/' }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indent by depth so the path hierarchy is legible without drawing an outline.
        if (depth > 0) {
            Spacer(Modifier.width((depth * 14).dp))
            Box(
                Modifier
                    .size(width = 1.dp, height = 24.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(tag.display, style = MaterialTheme.typography.bodyLarge)
            Text(
                tag.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (account != null) {
            Text(
                account.removePrefix("Expenses:"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuleRow(rule: ItemRule, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.id ?: "rule ${rule.index}", style = MaterialTheme.typography.bodyMedium)
                if (rule.layer > 0u) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "yours",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = BbAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(BbAccentSoft)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                rule.keywords.take(4).joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                rule.account ?: "tags only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportedDocumentRow(doc: ImportedRuleDocument, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(doc.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                DATE_FORMAT.format(Date(doc.importedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${doc.displayName}")
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}

@Composable
private fun EmptyForQuery(query: String) {
    Text(
        if (query.isEmpty()) "Nothing here." else "No results for “$query”.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// MARK: - Detail screens

/** The rules that mention one tag, and where it files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDetailScreen(
    tag: ItemTag,
    book: RuleBook?,
    onOpenRule: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val account = remember(book, tag) {
        book?.categories()?.firstOrNull { it.path == tag.path }?.account
    }
    val rules = remember(book, tag) {
        (book?.rules() ?: emptyList()).filter { tag.path in it.tagPaths }
    }

    DetailScaffold(title = tag.display, onBack = onBack) {
        SettingsSection {
            LabeledDetailRow("Path", tag.path)
            if (account != null) {
                RowDivider()
                LabeledDetailRow("Account", account)
            }
        }
        if (rules.isEmpty()) {
            SettingsSection {
                Text(
                    "No rule assigns this tag directly. It may still appear on items via a more " +
                        "specific tag beneath it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSection(title = "Rules") {
                rules.forEachIndexed { i, rule ->
                    if (i > 0) RowDivider()
                    RuleRow(rule) { onOpenRule(rule.index.toInt()) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDetailScreen(rule: ItemRule, onBack: () -> Unit) {
    DetailScaffold(title = rule.id ?: "Rule", onBack = onBack) {
        SettingsSection {
            LabeledDetailRow("Source", if (rule.layer == 0u) "Built in" else "Imported")
            RowDivider()
            LabeledDetailRow("Priority", "${rule.priority}")
            if (rule.exactOnly) {
                RowDivider()
                LabeledDetailRow("Matching", "Exact only")
            }
            RowDivider()
            LabeledDetailRow("Account", rule.account ?: "None — tags only")
        }
        if (rule.tagPaths.isNotEmpty()) {
            SettingsSection(title = "Tags") {
                rule.tagPaths.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (rule.removeTags.isNotEmpty()) {
            SettingsSection(
                title = "Removes",
                footer = "These tags are taken away when this rule matches, even if another rule " +
                    "added them.",
            ) {
                rule.removeTags.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (rule.disables.isNotEmpty()) {
            SettingsSection(
                title = "Disables",
                footer = "When this rule matches, these rules are ignored entirely.",
            ) {
                rule.disables.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        SettingsSection(title = "Keywords (${rule.keywords.size})") {
            rule.keywords.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Type an item description and see exactly how it classifies, and why.
 *
 * This is the screen that answers "why is my yogurt filed under Snacks?" —
 * nothing else in BeanBeaver could say.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainItemScreen(
    book: RuleBook?,
    /** Pre-filled when opened from a scanned item. */
    initialDescription: String = "",
    onBack: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialDescription) }
    val explanation = remember(book, text) {
        if (book == null || text.isBlank()) null else book.explain(text)
    }

    DetailScaffold(title = "Test an Item", onBack = onBack) {
        SettingsSection(footer = "Type a line as it appears on a receipt.") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("e.g. KS ORG 2% MILK") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (explanation != null) {
            SettingsSection(title = "Result") {
                LabeledDetailRow("Account", explanation.account ?: "Uncategorized")
                if (explanation.tags.isNotEmpty()) {
                    RowDivider()
                    val display = tagDisplay(explanation.tags)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        display.primary?.let { CategoryChip(it) }
                        if (display.rest.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                display.rest.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SettingsSection(
                title = "Matched rules",
                footer = "Strongest first. The checked rule supplied the account; every rule listed " +
                    "contributed its tags.",
            ) {
                if (explanation.matches.isEmpty()) {
                    Text(
                        "No rule matched this line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                explanation.matches.forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m.isCategoryWinner) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Supplied the account",
                                    tint = BbAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                m.ruleId ?: "rule ${m.ruleIndex}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (m.isExact) "Exact" else "Fuzzy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "matched “${m.matchedKeyword}”  ·  priority ${m.priority}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (m.tagPaths.isNotEmpty()) {
                            Text(
                                m.tagPaths.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
        ) { content() }
    }
}

@Composable
private fun LabeledDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Filtering

private fun filterTags(book: RuleBook?, query: String): List<ItemTag> {
    val all = book?.tags() ?: emptyList()
    if (query.isEmpty()) return all
    val q = query.lowercase()
    return all.filter { q in it.path.lowercase() || q in it.display.lowercase() }
}

private fun filterCategories(book: RuleBook?, query: String) =
    (book?.categories() ?: emptyList()).let { all ->
        if (query.isEmpty()) return@let all
        val q = query.lowercase()
        all.filter { q in it.path.lowercase() || q in it.account.lowercase() }
    }

private fun filterRules(book: RuleBook?, query: String): List<ItemRule> {
    val all = book?.rules() ?: emptyList()
    if (query.isEmpty()) return all
    val q = query.lowercase()
    return all.filter { rule ->
        q in (rule.id ?: "").lowercase() ||
            rule.keywords.any { q in it.lowercase() } ||
            rule.tagPaths.any { q in it.lowercase() } ||
            q in (rule.account ?: "").lowercase()
    }
}

/** The picked file's display name, for the "Imported" list. */
private fun documentName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
