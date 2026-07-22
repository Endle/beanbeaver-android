package com.beanbeaver.app.github

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens a GitHub pull request that files a scanned receipt into the user's ledger
 * repo as its own folder (`.beancount` + `.json` + `.jpg` side by side). Kotlin
 * twin of iOS `GitHubLedger` — pure GitHub REST over HTTPS, no on-device git:
 *
 *   1. resolve the repo's default branch and read its head commit,
 *   2. create a fresh branch off it,
 *   3. PUT each of the receipt's files onto that branch (one commit each),
 *   4. open a PR from that branch into the default branch.
 *
 * Idempotent: every path is content-addressed (the sha8 token), so a file that's
 * present is identical, and a re-export of an already-filed receipt reports itself
 * instead of opening an empty PR.
 */
object GitHubLedger {
    /** Root folder everything scanned lives under. */
    const val ROOT_DIR = "beanbeaver_receipts"

    data class Config(val owner: String, val repo: String, val token: String)

    /** One file destined for the repo, with the commit message that carries it. */
    private data class RepoFile(val path: String, val data: ByteArray, val message: String)

    /** One receipt resolved to where it lands in the repo. */
    private class Filing(val entry: LedgerEntry) {
        val folder: String
        val basename: String
        val dateToken: String

        init {
            // `<merchant>-<yyyymmdd|unknowndate>-<sha8>`: the identity token is the
            // same one baked into the transaction and `documentRelpath`.
            val idParts = entry.beanbeaverId?.split("-")
            if (idParts == null || idParts.size != 3) {
                throw GitHubException(
                    "This receipt has no captured photo to derive an identity from — can't file it under GitHub.")
            }
            dateToken = idParts[1]
            val sha8 = idParts[2]
            folder = "$ROOT_DIR/${entry.merchantSlug}-$dateToken-$sha8"
            basename = "${entry.merchantSlug}-$dateToken-${hhmm()}-$sha8"
        }

        val files: List<RepoFile>
            get() {
                val out = mutableListOf(
                    RepoFile("$folder/$basename.beancount", entry.beancount.toByteArray(),
                        "BeanBeaver: add receipt transaction"),
                )
                entry.jsonBytes?.let {
                    out.add(RepoFile("$folder/$basename.json", it, "BeanBeaver: add receipt JSON"))
                }
                entry.documentBytes?.let {
                    out.add(RepoFile("$folder/$basename.jpg", it, "BeanBeaver: add receipt image"))
                }
                return out
            }
    }

    /**
     * The whole batch goes onto one branch and into one pull request. Report each
     * step through [onProgress] — this is several sequential round trips.
     */
    suspend fun openPullRequest(
        cfg: Config,
        entries: List<LedgerEntry>,
        onProgress: (String) -> Unit,
    ): String {
        val filings = entries.map { Filing(it) }
        val repoRoot = "/repos/${cfg.owner}/${cfg.repo}"

        // 0. Default branch — we always target it (no branch to pick).
        onProgress("Reading ${cfg.owner}/${cfg.repo}…")
        val repoInfo = api(cfg, "GET", repoRoot)
        val base = repoInfo.optString("default_branch")
            .ifEmpty { throw GitHubException("Repository not found or not accessible.") }

        // 1. Head commit of the base branch.
        val ref = api(cfg, "GET", "$repoRoot/git/ref/heads/$base")
        val baseSha = ref.getJSONObject("object").getString("sha")

        // 2. Work out what's actually missing before touching anything, so an
        //    already-filed batch reports itself instead of stranding a branch.
        val pending = mutableListOf<List<RepoFile>>()
        filings.forEachIndexed { index, filing ->
            onProgress(
                if (filings.size == 1) "Checking what's already filed…"
                else "Checking receipt ${index + 1} of ${filings.size}…")
            val missing = filing.files.filterNot { fileExists(cfg, repoRoot, it.path, base) }
            if (missing.isNotEmpty()) pending.add(missing)
        }
        if (pending.isEmpty()) {
            throw GitHubException(
                if (filings.size == 1)
                    "This receipt is already filed in the repo — nothing to open a pull request for."
                else
                    "All ${filings.size} receipts are already filed in the repo — nothing to open a pull request for.")
        }

        // 3. New branch off the base head.
        onProgress("Creating the branch…")
        val branch = "beanbeaver/receipt-${branchStamp()}"
        api(cfg, "POST", "$repoRoot/git/refs",
            JSONObject().put("ref", "refs/heads/$branch").put("sha", baseSha))

        // 4. One commit per file — the contents API can't batch files into one
        //    commit, and each builds on the last, so this is serial by necessity.
        pending.forEachIndexed { position, group ->
            onProgress(
                if (pending.size == 1) "Uploading the receipt…"
                else "Uploading receipt ${position + 1} of ${pending.size}…")
            group.forEach { putFile(cfg, repoRoot, it, branch) }
        }

        // 5. Open the PR.
        onProgress("Opening the pull request…")
        val pr = api(cfg, "POST", "$repoRoot/pulls",
            JSONObject()
                .put("title", title(filings))
                .put("head", branch)
                .put("base", base)
                .put("body", prBody(filings)))
        return pr.optString("html_url")
            .ifEmpty { throw GitHubException("Pull request created but its URL was missing.") }
    }

    private fun title(filings: List<Filing>): String {
        val only = filings.singleOrNull() ?: return "Add ${filings.size} receipts"
        return "Add receipt: ${only.entry.merchantSlug} ${only.dateToken}"
    }

    private fun prBody(filings: List<Filing>): String {
        val only = filings.singleOrNull()
            ?: return "Filed ${filings.size} scanned receipts with BeanBeaver Android.\n\n" +
                filings.joinToString("\n") { "- `${it.folder}/`" }
        return "Filed a scanned receipt under `${only.folder}/` with BeanBeaver Android."
    }

    /** Whether `path` already exists at `ref`. Content-addressed, so present = identical. */
    private suspend fun fileExists(cfg: Config, repoRoot: String, path: String, ref: String): Boolean {
        return try {
            api(cfg, "GET", "$repoRoot/contents/${encodePath(path)}?ref=$ref")
            true
        } catch (e: HttpStatusException) {
            if (e.status == 404) false else throw e
        }
    }

    private suspend fun putFile(cfg: Config, repoRoot: String, file: RepoFile, branch: String) {
        api(cfg, "PUT", "$repoRoot/contents/${encodePath(file.path)}",
            JSONObject()
                .put("message", file.message)
                .put("content", Base64.encodeToString(file.data, Base64.NO_WRAP))
                .put("branch", branch))
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun branchStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun hhmm(): String = SimpleDateFormat("HHmm", Locale.US).format(Date())

    // MARK: - Transport

    /** A non-2xx that callers distinguish (404 = "file not found" for `fileExists`). */
    private class HttpStatusException(val status: Int, message: String) : Exception(message)

    private suspend fun api(
        cfg: Config, method: String, pathAndQuery: String, body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com$pathAndQuery").openConnection() as HttpURLConnection
        val code: Int
        val text: String
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            text = stream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw GitHubException("GitHub $method $pathAndQuery failed: ${e.message}")
        } finally {
            conn.disconnect()
        }

        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }
                .getOrNull()?.ifEmpty { null } ?: "HTTP $code"
            if (code == 404) throw HttpStatusException(404, message)
            throw GitHubException("GitHub: $message")
        }
        try {
            JSONObject(text)
        } catch (e: Exception) {
            throw GitHubException("Couldn't read GitHub's response ($pathAndQuery).")
        }
    }
}
