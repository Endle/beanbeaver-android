package com.zhenbo.beanbeaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.debug.DataDump
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

/**
 * "Dump All Data" — everything the app has written to this device, by name and
 * size. Android twin of iOS `DataDumpView`. Values are shown for plain settings
 * only; the token and every file's contents are reported by name/size alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataDumpScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val dump = remember { DataDump.capture(context) }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Stored Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("BeanBeaver data dump", dump.plainText(context)),
                        )
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy dump")
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
            Text(
                "Everything BeanBeaver has written to this device. Settings show their values; " +
                    "the GitHub token and file contents are listed by name and size only, so this " +
                    "screen can't leak them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DumpSection("Preferences (${dump.preferences.size})") {
                if (dump.preferences.isEmpty()) {
                    EmptyRow()
                } else {
                    dump.preferences.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        KeyValueRow(e.key, e.value)
                    }
                }
            }

            DumpSection("Secrets (${dump.secrets.size})") {
                if (dump.secrets.isEmpty()) {
                    EmptyRow()
                } else {
                    dump.secrets.forEach { KeyValueRow(it.key, it.value) }
                }
            }

            DumpSection("Files on disk (${dump.files.size})") {
                if (dump.files.isEmpty()) {
                    EmptyRow()
                } else {
                    dump.files.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        KeyValueRow(f.relativePath, formatBytes(f.byteCount))
                    }
                }
            }
        }
    }
}

@Composable
private fun DumpSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        BbCard { SelectionContainer { Column { content() } } }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            key,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyRow() {
    Text(
        "(empty)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
