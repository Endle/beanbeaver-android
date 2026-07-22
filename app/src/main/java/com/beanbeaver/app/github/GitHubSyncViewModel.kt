package com.beanbeaver.app.github

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beanbeaver.app.debug.DebugInfoStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the GitHub-sync state and orchestrates the connect flow + PR export,
 * folding iOS's `GitHubConnection` + `LedgerExporter` into one ViewModel. Token
 * lives in [TokenStore] (Keystore-encrypted); the chosen owner/repo in
 * SharedPreferences. The UI observes the StateFlows.
 */
class GitHubSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenStore = TokenStore(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The connect walkthrough's state (browser authorize → install gate → done). */
    sealed interface ConnectPhase {
        data object Idle : ConnectPhase
        data object Starting : ConnectPhase
        data class AwaitingAuthorization(val userCode: String) : ConnectPhase
        data object VerifyingInstall : ConnectPhase
        data class NeedsInstall(val installUrl: String) : ConnectPhase
        data class Failed(val message: String) : ConnectPhase
    }

    /** Whether the chosen repo is reachable with the current token. */
    sealed interface Access {
        data object Idle : Access
        data object Checking : Access
        data class Ok(val defaultBranch: String, val canPush: Boolean) : Access
        data class Bad(val message: String) : Access
    }

    /** The outcome of an export attempt, surfaced as a dialog. */
    data class ExportResult(val title: String, val message: String, val url: String?, val isError: Boolean)

    private val _connectPhase = MutableStateFlow<ConnectPhase>(ConnectPhase.Idle)
    val connectPhase: StateFlow<ConnectPhase> = _connectPhase.asStateFlow()

    private var token: String? = tokenStore.get()

    private val _account = MutableStateFlow<String?>(null)
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _repos = MutableStateFlow<List<GitHubApp.Repo>>(emptyList())
    val repos: StateFlow<List<GitHubApp.Repo>> = _repos.asStateFlow()

    private val _owner = MutableStateFlow(prefs.getString(KEY_OWNER, "") ?: "")
    val owner: StateFlow<String> = _owner.asStateFlow()

    private val _repo = MutableStateFlow(prefs.getString(KEY_REPO, "") ?: "")
    val repo: StateFlow<String> = _repo.asStateFlow()

    private val _access = MutableStateFlow<Access>(Access.Idle)
    val access: StateFlow<Access> = _access.asStateFlow()

    private val _connected = MutableStateFlow(!token.isNullOrEmpty())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _configured = MutableStateFlow(false)
    /** Connected **and** a repo chosen — the gate on offering the Export button. */
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _exportRunning = MutableStateFlow(false)
    val exportRunning: StateFlow<Boolean> = _exportRunning.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    private var connectJob: Job? = null
    private var pendingToken: String? = null

    val isConfigured: Boolean
        get() = !token.isNullOrEmpty() && _owner.value.isNotBlank() && _repo.value.isNotBlank()

    private val isBusyConnecting: Boolean
        get() = when (_connectPhase.value) {
            is ConnectPhase.Starting, is ConnectPhase.AwaitingAuthorization,
            is ConnectPhase.VerifyingInstall -> true
            else -> false
        }

    init {
        recomputeConfigured()
        if (!token.isNullOrEmpty()) refreshAccountAndRepos()
    }

    // MARK: - Connect flow

    fun connect(openUrl: (String) -> Unit) {
        if (isBusyConnecting) return
        _connectPhase.value = ConnectPhase.Starting
        connectJob = viewModelScope.launch {
            try {
                val device = GitHubApp.requestDeviceCode()
                _connectPhase.value = ConnectPhase.AwaitingAuthorization(device.userCode)
                openUrl(device.verificationUriComplete ?: device.verificationUri)
                val newToken = GitHubApp.pollForToken(device)
                finishConnect(newToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _connectPhase.value = ConnectPhase.Idle
            } catch (e: Exception) {
                _connectPhase.value = ConnectPhase.Failed(e.message ?: "Connection failed.")
            }
        }
    }

    /** After the user installs the app (the "Continue" button), re-check + complete. */
    fun recheckInstallation() {
        val pending = pendingToken ?: return
        _connectPhase.value = ConnectPhase.VerifyingInstall
        connectJob = viewModelScope.launch { finishConnect(pending) }
    }

    private suspend fun finishConnect(newToken: String) {
        val installUrl = GitHubApp.installUrl
        if (installUrl == null) {
            complete(newToken)
            return
        }
        _connectPhase.value = ConnectPhase.VerifyingInstall
        try {
            if (GitHubApp.hasInstallation(newToken)) {
                complete(newToken)
            } else {
                pendingToken = newToken
                _connectPhase.value = ConnectPhase.NeedsInstall(installUrl)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _connectPhase.value = ConnectPhase.Idle
        } catch (e: Exception) {
            _connectPhase.value = ConnectPhase.Failed(e.message ?: "Failed to verify installation.")
        }
    }

    private fun complete(newToken: String) {
        tokenStore.set(newToken)
        token = newToken
        pendingToken = null
        _connected.value = true
        recomputeConfigured()
        _connectPhase.value = ConnectPhase.Idle
        refreshAccountAndRepos()
    }

    fun cancelConnect() {
        connectJob?.cancel()
        pendingToken = null
        _connectPhase.value = ConnectPhase.Idle
    }

    fun disconnect() {
        connectJob?.cancel()
        tokenStore.clear()
        token = null
        pendingToken = null
        _connected.value = false
        _account.value = null
        _repos.value = emptyList()
        _access.value = Access.Idle
        _connectPhase.value = ConnectPhase.Idle
        recomputeConfigured()
    }

    // MARK: - Repo selection

    private fun refreshAccountAndRepos() {
        val t = token ?: return
        viewModelScope.launch {
            _account.value = runCatching { GitHubApp.fetchLogin(t) }.getOrNull()
        }
        loadRepos()
        verifyAccess()
    }

    fun loadRepos() {
        val t = token ?: return
        viewModelScope.launch {
            _repos.value = runCatching { GitHubApp.listInstallationRepos(t) }.getOrDefault(emptyList())
        }
    }

    fun selectRepo(owner: String, repo: String) {
        _owner.value = owner
        _repo.value = repo
        prefs.edit().putString(KEY_OWNER, owner).putString(KEY_REPO, repo).apply()
        recomputeConfigured()
        verifyAccess()
    }

    fun verifyAccess() {
        val t = token ?: run { _access.value = Access.Idle; return }
        val o = _owner.value
        val r = _repo.value
        if (o.isBlank() || r.isBlank()) {
            _access.value = Access.Idle
            return
        }
        viewModelScope.launch {
            _access.value = Access.Checking
            _access.value = try {
                val a = GitHubApp.checkRepoAccess(o, r, t)
                Access.Ok(a.defaultBranch, a.canPush)
            } catch (e: Exception) {
                Access.Bad(e.message ?: "Can't reach that repo.")
            }
        }
    }

    private fun recomputeConfigured() {
        _configured.value = isConfigured
    }

    // MARK: - Export

    fun export(entry: LedgerEntry) = export(listOf(entry))

    /**
     * File one or more receipts into the ledger repo as a single pull request (the
     * whole batch goes onto one branch). [onComplete] reports success so a batch
     * caller can drain the parsed drafts; it's a no-op for the single-scan path.
     */
    fun export(entries: List<LedgerEntry>, onComplete: (Boolean) -> Unit = {}) {
        if (_exportRunning.value || !isConfigured || entries.isEmpty()) return
        val t = token ?: return
        _exportRunning.value = true
        _exportMessage.value = null
        viewModelScope.launch {
            var ok = false
            try {
                val cfg = GitHubLedger.Config(_owner.value.trim(), _repo.value.trim(), t)
                val url = GitHubLedger.openPullRequest(cfg, entries) { message ->
                    _exportMessage.value = message
                }
                _exportResult.value = ExportResult("Pull request opened", url, url, isError = false)
                ok = true
            } catch (e: Exception) {
                val message = e.message ?: "Export failed."
                DebugInfoStore.recordExportFailure(getApplication(), "export to GitHub", message)
                _exportResult.value = ExportResult("Export failed", message, null, isError = true)
            } finally {
                _exportRunning.value = false
                _exportMessage.value = null
                onComplete(ok)
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    companion object {
        private const val PREFS = "beanbeaver_github"
        private const val KEY_OWNER = "githubOwner"
        private const val KEY_REPO = "githubRepo"
    }
}
