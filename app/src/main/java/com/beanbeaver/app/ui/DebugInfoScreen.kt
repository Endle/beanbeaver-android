package com.beanbeaver.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.beanbeaver.app.debug.DebugInfoStore
import com.beanbeaver.app.ui.theme.groupedBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists what the opt-in "Store detailed debug info" setting captured — one row
 * per stored scan/export event, tap to read the JSON. Android twin of iOS
 * `DebugInfoListView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(DebugInfoStore.entries(context)) }
    var viewing by remember { mutableStateOf<DebugInfoStore.StoredEntry?>(null) }

    val open = viewing
    if (open != null) {
        DebugEntryDetail(entry = open, onBack = { viewing = null })
        return
    }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Stored Debug Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = {
                            DebugInfoStore.clearAll(context)
                            entries = DebugInfoStore.entries(context)
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear all")
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
            if (entries.isEmpty()) {
                Text(
                    "Nothing stored. Turn on \"Store detailed debug info\" in Settings, then scan or export — " +
                        "each event is captured here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BbCard {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        DebugEntryRow(entry) { viewing = entry }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugEntryRow(entry: DebugInfoStore.StoredEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.outcome.ifEmpty { "entry" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                timestamp(entry.modified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${entry.byteCount} B",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugEntryDetail(entry: DebugInfoStore.StoredEntry, onBack: () -> Unit) {
    val text = remember(entry) { runCatching { entry.file.readText() }.getOrDefault("(unreadable)") }
    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(entry.outcome.ifEmpty { "Debug entry" }) },
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
            BbCard {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.US).format(Date(millis))
