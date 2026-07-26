package com.zhenbo.beanbeaver.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

/**
 * Reads a Markdown document that ships inside the APK. Android twin of iOS
 * `BundledDocument`.
 *
 * `PRIVACY.md` and `THIRD_PARTY_NOTICES.md` live at the repo root and are copied
 * into `assets/legal/` by the `syncLegalDocs` Gradle task rather than duplicated
 * there by hand, so the file a reader sees in the repo and the file the app
 * displays can't drift. Bundling them also means both are readable with no
 * network: a privacy policy you can only read by leaving the app to visit GitHub
 * is a poor promise.
 */
object BundledDocument {
    fun text(context: Context, name: String): String? =
        runCatching {
            context.assets.open("legal/$name").bufferedReader().use { it.readText() }
        }.getOrNull()
}

/**
 * The privacy policy. Rendered with light Markdown styling — it's prose, and a
 * wall of monospace would read like a licence file.
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        BundledDocument.text(context, "PRIVACY.md")
            ?: "The privacy policy is unavailable in this build. It can be read at " +
            "https://github.com/Endle/beanbeaver-android/blob/master/PRIVACY.md"
    }
    DocumentScaffold(title = "Privacy Policy", onBack = onBack) {
        MarkdownProse(text)
    }
}

/**
 * The third-party notices, shown verbatim in monospace. This one is a legal
 * document — every crate in the graph plus the full text of the licences — and
 * the licences that require their text to travel with the binary (Apache-2.0 for
 * the PP-OCRv5 models, MIT for ONNX Runtime, MPL-2.0 for UniFFI) are only
 * satisfied if it actually ships. Showing it raw is the point: it should look
 * exactly like the file.
 */
@Composable
fun AcknowledgementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        BundledDocument.text(context, "THIRD_PARTY_NOTICES.md")
            ?: "Third-party notices are unavailable in this build. " +
            "They can be read at https://github.com/Endle/beanbeaver-android"
    }
    DocumentScaffold(title = "Acknowledgements", onBack = onBack) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
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
        ) {
            content()
            Spacer(Modifier.padding(8.dp))
        }
    }
}

/**
 * A deliberately small Markdown renderer: enough for the headings, bullets and
 * paragraphs in `PRIVACY.md`, and nothing more.
 *
 * Markdown wraps prose across source lines and only starts a new paragraph at a
 * blank line, so consecutive prose lines are joined. Rendering one source line
 * per paragraph (the obvious implementation) shreds a hard-wrapped file into
 * fragments broken mid-sentence.
 */
@Composable
private fun MarkdownProse(markdown: String) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                blocks.forEach { block ->
                    when (block) {
                        is Block.Title -> Text(
                            block.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        is Block.Heading -> Text(
                            block.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                        is Block.Item -> Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(block.marker, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stripInlineMarkup(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is Block.Paragraph -> Text(
                            stripInlineMarkup(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Block {
    data class Title(val text: String) : Block
    data class Heading(val text: String) : Block
    /** [marker] is the bullet glyph or the list number ("1."), kept so an ordered
     *  list renders as one. */
    data class Item(val marker: String, val text: String) : Block
    data class Paragraph(val text: String) : Block
}

private fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    var afterBlankLine = true

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks.add(Block.Paragraph(paragraph.joinToString(" ")))
            paragraph.clear()
        }
    }

    markdown.lines().forEach { line ->
        val trimmed = line.trim()
        val wasAfterBlank = afterBlankLine
        afterBlankLine = trimmed.isEmpty()

        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("## ") -> {
                flush(); blocks.add(Block.Heading(trimmed.removePrefix("## ")))
            }
            trimmed.startsWith("# ") -> {
                flush(); blocks.add(Block.Title(trimmed.removePrefix("# ")))
            }
            trimmed.startsWith("- ") -> {
                flush(); blocks.add(Block.Item("•", trimmed.removePrefix("- ")))
            }
            orderedMarker(trimmed) != null -> {
                flush()
                val (marker, rest) = orderedMarker(trimmed)!!
                blocks.add(Block.Item(marker, rest))
            }
            // A wrapped line inside a list item continues that item, but only when
            // it directly follows it — otherwise the first paragraph after a list
            // would be swallowed into the last bullet.
            paragraph.isEmpty() && !wasAfterBlank && blocks.lastOrNull() is Block.Item -> {
                val last = blocks.removeAt(blocks.size - 1) as Block.Item
                blocks.add(Block.Item(last.marker, "${last.text} $trimmed"))
            }
            else -> paragraph.add(trimmed)
        }
    }
    flush()
    return blocks
}

/** Splits an ordered-list line ("2. Some text") into its marker and text. Null for
 *  anything else — including prose that merely starts with a number, since that
 *  needs the "N. " shape to match. */
private fun orderedMarker(line: String): Pair<String, String>? {
    val digits = line.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    val rest = line.drop(digits.length)
    if (!rest.startsWith(". ")) return null
    return "$digits." to rest.drop(2)
}

/** Compose's Text has no Markdown support, so inline emphasis and link syntax are
 *  flattened to their visible text rather than left as literal `**`/`[]()`. */
private fun stripInlineMarkup(text: String): String =
    text.replace(Regex("""\[([^\]]+)]\(([^)]+)\)""")) { m ->
        val label = m.groupValues[1]
        val url = m.groupValues[2]
        if (label == url) url else "$label ($url)"
    }
        .replace("**", "")
        .replace("`", "")
