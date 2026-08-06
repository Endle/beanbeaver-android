package com.zhenbo.beanbeaver.ui

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.receipt.ItemRuleStore
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.cardBackground
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bb_receipt_ffi.ItemCategory
import uniffi.bb_receipt_ffi.ItemExplanation
import uniffi.bb_receipt_ffi.ItemRule
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.RuleBook
import uniffi.bb_receipt_ffi.RuleMatchInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browse the classification ruleset: which tags exist, which accounts they map
 * to, and which keyword rules produce them — the Android twin of iOS
 * `ItemRulesView`.
 *
 * Read-only by design. The rule format may still change, so this deliberately
 * offers no rule editor — the one write gesture is importing a document, which
 * needs no UI for the format itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ItemRuleStore.ensureLoaded(context)

    var axis by rememberSaveable { mutableStateOf(0) } // 0=Tags 1=Accounts 2=Rules
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) } // bumped after any store mutation
    var showExplain by rememberSaveable { mutableStateOf(false) }
    var detailTag by remember { mutableStateOf<ItemTag?>(null) }
    var detailRule by remember { mutableStateOf<ItemRule?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Re-read the store each recomposition (refresh bumps after import/remove).
    val book = ItemRuleStore.ruleBook
    val docs = ItemRuleStore.documentsList
    val loadError = ItemRuleStore.currentLoadError

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val toml = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }?.toString(Charsets.UTF_8)
                        ?: throw IllegalStateException("Couldn't read that file.")
                    ItemRuleStore.importDocument(context, displayName(context, uri), toml)
                }
            }
            result.onSuccess { summary ->
                val rules = summary.rules
                importMessage =
                    "Added $rules rule${if (rules == 1) "" else "s"}" +
                        (if (summary.tags > 0) " and ${summary.tags} new tag${if (summary.tags == 1) "" else "s"}." else ".")
            }.onFailure { importError = it.message ?: "Couldn't import that file." }
            refresh++
        }
    }

    if (showExplain) {
        ExplainItemScreen(book = book, onBack = { showExplain = false })
        return
    }
    detailTag?.let { tag ->
        TagDetailScreen(tag = tag, book = book, onBack = { detailTag = null })
        return
    }
    detailRule?.let { rule ->
        RuleDetailScreen(rule = rule, onBack = { detailRule = null })
        return
    }

    BackHandler(onBack = onBack)

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
                    TextButton(onClick = { showExplain = true }) { Text("Test") }
                    TextButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }) { Text("Import") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadError != null) {
                Card(colors = CardDefaults.cardColors(containerColor = BbAccentSoft)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = BbAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "Your rules are not being applied: $loadError",
                            style = MaterialTheme.typography.bodySmall,
                            color = BbAccent,
                        )
                    }
                }
            }

            TabRow(selectedTabIndex = axis) {
                Tab(selected = axis == 0, onClick = { axis = 0 }, text = { Text("Tags") })
                Tab(selected = axis == 1, onClick = { axis = 1 }, text = { Text("Accounts") })
                Tab(selected = axis == 2, onClick = { axis = 2 }, text = { Text("Rules") })
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search tags, accounts, keywords") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { RulesetHeader(axis = axis) }
                when (axis) {
                    0 -> items(filteredTags(book, query), key = { it.path }) { tag ->
                        RulesetRow(onClick = { detailTag = tag }) {
                            TagRow(tag = tag, account = accountFor(book, tag.path))
                        }
                    }
                    1 -> items(filteredCategories(book, query), key = { it.path }) { cat ->
                        RulesetRow(onClick = null) {
                            AccountRow(cat)
                        }
                    }
                    2 -> items(filteredRules(book, query), key = { it.index }) { rule ->
                        RulesetRow(onClick = { detailRule = rule }) {
                            RuleRow(rule)
                        }
                    }
                }
                if (docs.isNotEmpty()) {
                    item {
                        SectionTitle("Imported (${docs.size})")
                    }
                    items(docs, key = { it.id }) { doc ->
                        ImportedDocumentRow(
                            doc = doc,
                            onDelete = {
                                ItemRuleStore.remove(context, doc.id)
                                refresh++
                            },
                        )
                    }
                    item {
                        Text(
                            "Rules from these files layer on top of the built-in ones and apply to every new scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
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
            confirmButton = {
                TextButton(onClick = { importMessage = null }) { Text("OK") }
            },
        )
    }
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn't Import") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            },
        )
    }
}

private val IMPORT_MIME = arrayOf("text/plain", "text/x-toml", "application/octet-stream", "*/*")

// MARK: - Filtering

private fun filteredTags(book: RuleBook?, query: String): List<ItemTag> {
    val all = book?.tags() ?: emptyList()
    if (query.isBlank()) return all
    val q = query.lowercase()
    return all.filter { it.path.lowercase().contains(q) || it.display.lowercase().contains(q) }
}

private fun filteredCategories(book: RuleBook?, query: String): List<ItemCategory> {
    val all = book?.categories() ?: emptyList()
    if (query.isBlank()) return all
    val q = query.lowercase()
    return all.filter { it.path.lowercase().contains(q) || it.account.lowercase().contains(q) }
}

private fun filteredRules(book: RuleBook?, query: String): List<ItemRule> {
    val all = book?.rules() ?: emptyList()
    if (query.isBlank()) return all
    val q = query.lowercase()
    return all.filter { rule ->
        (rule.id ?: "").lowercase().contains(q) ||
            rule.keywords.any { it.lowercase().contains(q) } ||
            rule.tagPaths.any { it.lowercase().contains(q) } ||
            (rule.account ?: "").lowercase().contains(q)
    }
}

private fun accountFor(book: RuleBook?, path: String): String? =
    book?.categories()?.firstOrNull { it.path == path }?.account

// MARK: - Rows

@Composable
private fun RulesetHeader(axis: Int) {
    val footer = when (axis) {
        0 -> "A tag is a path, so the same word can sit under two parents. Tags with no account " +
            "listed only describe the item — the account comes from a parent or from the winning rule."
        1 -> "Where each tag lands in your ledger. An imported document can remap any of these " +
            "without touching a rule."
        else -> "Rules are matched against each item's text. When several match, the highest-priority " +
            "one supplies the account; every match contributes its tags."
    }
    Text(
        footer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun RulesetRow(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun TagRow(tag: ItemTag, account: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tag.display, style = MaterialTheme.typography.bodyLarge)
            Text(
                tag.path,
                style = MaterialTheme.typography.bodySmall,
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
    }
}

@Composable
private fun AccountRow(cat: ItemCategory) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(cat.account, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            cat.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuleRow(rule: ItemRule) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                rule.id ?: "rule ${rule.index}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (rule.layer > 0u) {
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            rule.account ?: "tags only",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportedDocumentRow(doc: com.zhenbo.beanbeaver.receipt.ImportedRuleDocument, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(doc.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(doc.importedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${doc.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Detail screens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDetailScreen(tag: ItemTag, book: RuleBook?, onBack: () -> Unit) {
    DetailScaffold(title = tag.display, onBack = onBack) {
        SettingsSection(title = null) {
            LabeledRow("Path", tag.path)
            accountFor(book, tag.path)?.let { Spacer(Modifier.size(8.dp)); LabeledRow("Account", it) }
        }
        val rules = (book?.rules() ?: emptyList()).filter { it.tagPaths.contains(tag.path) }
        if (rules.isEmpty()) {
            SettingsSection(
                footer = "No rule assigns this tag directly. It may still appear on items via a more " +
                    "specific tag beneath it.",
            ) {
                Text("No rule assigns this tag directly.")
            }
        } else {
            SettingsSection(title = "Rules", footer = "Rules that assign this tag directly.") {
                rules.forEach { rule ->
                    RuleMiniRow(rule)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDetailScreen(rule: ItemRule, onBack: () -> Unit) {
    DetailScaffold(title = rule.id ?: "Rule", onBack = onBack) {
        SettingsSection(title = null) {
            LabeledRow("Source", if (rule.layer == 0u) "Built in" else "Imported")
            LabeledRow("Priority", rule.priority.toString())
            if (rule.exactOnly) LabeledRow("Matching", "Exact only")
            LabeledRow("Account", rule.account ?: "None — tags only")
        }
        if (rule.tagPaths.isNotEmpty()) {
            SettingsSection(title = "Tags") {
                rule.tagPaths.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (rule.removeTags.isNotEmpty()) {
            SettingsSection(
                title = "Removes",
                footer = "These tags are taken away when this rule matches, even if another rule added them.",
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
        if (rule.keywords.isNotEmpty()) {
            SettingsSection(title = "Keywords (${rule.keywords.size})") {
                rule.keywords.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun RuleMiniRow(rule: ItemRule) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(rule.id ?: "rule ${rule.index}", style = MaterialTheme.typography.bodyMedium)
        Text(
            rule.keywords.take(5).joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// MARK: - Test an Item

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplainItemScreen(book: RuleBook?, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val explanation: ItemExplanation? =
        if (book == null || text.isBlank()) null else book.explain(text)

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Test an Item") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("e.g. KS ORG 2% MILK") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (explanation != null) {
                SettingsSection(
                    footer = "Type a line as it appears on a receipt.",
                ) {
                    Text("Account: ${explanation.account ?: "Uncategorized"}", style = MaterialTheme.typography.bodyLarge)
                    val display = tagDisplay(explanation.tags)
                    if (display.primary != null) {
                        Spacer(Modifier.size(6.dp))
                        CategoryChip(display.primary!!)
                        if (display.rest.isNotEmpty()) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                display.rest.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SettingsSection(
                    title = "Matched rules",
                    footer = "Strongest first. The checked rule supplied the account; every rule " +
                        "listed contributed its tags.",
                ) {
                    if (explanation.matches.isEmpty()) {
                        Text(
                            "No rule matched this line.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        explanation.matches.forEachIndexed { i, m ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            MatchRow(m)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(m: RuleMatchInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.isCategoryWinner) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Supplies the account",
                    tint = BbAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                m.ruleId ?: "rule ${m.ruleIndex}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (m.isExact) "Exact" else "Fuzzy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "matched \"${m.matchedKeyword}\"  ·  priority ${m.priority}",
            style = MaterialTheme.typography.bodySmall,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The picked document's display name, via OpenableColumns. */
private fun displayName(context: Context, uri: android.net.Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: "rules.toml"
    }
    return "rules.toml"
}
