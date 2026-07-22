package com.beanbeaver.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beanbeaver.app.github.GitHubApp
import com.beanbeaver.app.github.GitHubSyncViewModel
import com.beanbeaver.app.github.GitHubSyncViewModel.ConnectPhase
import com.beanbeaver.app.ui.theme.BbAccent
import com.beanbeaver.app.ui.theme.groupedBackground

/**
 * Connect a GitHub account and pick the ledger repo receipts are filed to — the
 * Android twin of iOS `LedgerSettingsView`'s GitHub section. Each export opens a
 * pull request that files the scanned receipt into the repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSettingsScreen(vm: GitHubSyncViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    val connected by vm.connected.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("GitHub Sync") },
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
            if (connected) {
                ConnectedContent(vm, openUrl)
            } else {
                DisconnectedContent(vm, openUrl)
            }
        }
    }
}

// MARK: - Not connected

@Composable
private fun DisconnectedContent(vm: GitHubSyncViewModel, openUrl: (String) -> Unit) {
    val phase by vm.connectPhase.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    SettingsSection(
        title = "GitHub",
        footer = "Each export opens a pull request that files the scanned receipt into your ledger repo. " +
            "Connect your account: authorize in the browser, then install BeanBeaver on the one repo you pick — " +
            "it can't touch your other repos. The token is stored encrypted on this device.",
    ) {
        when (val p = phase) {
            is ConnectPhase.Idle,
            is ConnectPhase.Failed -> {
                if (p is ConnectPhase.Failed) {
                    Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                }
                Button(onClick = { vm.connect(openUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect GitHub", fontWeight = FontWeight.SemiBold)
                }
            }

            is ConnectPhase.Starting -> BusyRow("Contacting GitHub…")

            is ConnectPhase.AwaitingAuthorization -> {
                Text(
                    "Authorize in the browser that just opened, then come back. Enter this code if GitHub asks — tap to copy:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    p.userCode,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clipboard.setText(AnnotatedString(p.userCode)) },
                )
                Spacer(Modifier.size(12.dp))
                BusyRow("Waiting for authorization…")
                TextButton(onClick = { vm.cancelConnect() }) { Text("Cancel") }
            }

            is ConnectPhase.VerifyingInstall -> BusyRow("Verifying installation…")

            is ConnectPhase.NeedsInstall -> {
                Text(
                    "Almost there — install BeanBeaver on the ledger repo you want receipts filed to. " +
                        "The installation is what grants write access to that one repo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = { openUrl(p.installUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install on GitHub", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = { vm.recheckInstallation() }, modifier = Modifier.fillMaxWidth()) {
                    Text("I've Installed It — Continue")
                }
                TextButton(onClick = { vm.cancelConnect() }) { Text("Cancel") }
            }
        }
    }
}

// MARK: - Connected

@Composable
private fun ConnectedContent(vm: GitHubSyncViewModel, openUrl: (String) -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val repos by vm.repos.collectAsStateWithLifecycle()
    val owner by vm.owner.collectAsStateWithLifecycle()
    val repo by vm.repo.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()

    SettingsSection(title = "Account") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                account?.let { "Connected as @$it" } ?: "GitHub connected",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.disconnect() }) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    SettingsSection(
        title = "Repository",
        footer = "Receipts are filed as pull requests into this repo's default branch.",
    ) {
        RepoDropdown(repos = repos, current = if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else null) {
            vm.selectRepo(it.owner, it.name)
        }
        Spacer(Modifier.size(8.dp))
        AccessStatus(access)
    }

    SettingsSection(
        footer = "Don't see your repo? Install BeanBeaver on it, then it'll appear in the list. " +
            "Or type an owner/repo you've already granted access to.",
    ) {
        GitHubApp.installUrl?.let { url ->
            OutlinedButton(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Manage repos on GitHub")
            }
            Spacer(Modifier.size(8.dp))
        }
        ManualRepoEntry(owner = owner, repo = repo) { o, r -> vm.selectRepo(o, r) }
    }
}

@Composable
private fun RepoDropdown(
    repos: List<GitHubApp.Repo>,
    current: String?,
    onSelect: (GitHubApp.Repo) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current ?: "Choose a repository…", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (repos.isEmpty()) {
                DropdownMenuItem(text = { Text("No installed repos found") }, onClick = { expanded = false })
            }
            repos.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.fullName) },
                    onClick = { expanded = false; onSelect(r) },
                )
            }
        }
    }
}

@Composable
private fun ManualRepoEntry(owner: String, repo: String, onApply: (String, String) -> Unit) {
    var o by remember(owner) { mutableStateOf(owner) }
    var r by remember(repo) { mutableStateOf(repo) }
    OutlinedTextField(
        value = o,
        onValueChange = { o = it },
        label = { Text("Owner") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = r,
        onValueChange = { r = it },
        label = { Text("Repository") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    TextButton(
        onClick = { onApply(o.trim(), r.trim()) },
        enabled = o.isNotBlank() && r.isNotBlank(),
    ) {
        Text("Use this repo")
    }
}

@Composable
private fun AccessStatus(access: GitHubSyncViewModel.Access) {
    when (access) {
        is GitHubSyncViewModel.Access.Idle -> {}
        is GitHubSyncViewModel.Access.Checking -> BusyRow("Checking access…")
        is GitHubSyncViewModel.Access.Ok -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BbAccent, modifier = Modifier.size(18.dp))
            Text(
                "Can file pull requests to ${access.defaultBranch}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is GitHubSyncViewModel.Access.Bad -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(access.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BusyRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
