package com.zhenbo.beanbeaver.github

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 *   2. upload every file as a blob and hang them off one tree and one commit,
 *   3. point a fresh branch at that commit,
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

    /** One file destined for the repo. */
    private data class RepoFile(val path: String, val data: ByteArray)

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
                    RepoFile("$folder/$basename.beancount", entry.beancount.toByteArray()),
                )
                entry.jsonBytes?.let { out.add(RepoFile("$folder/$basename.json", it)) }
                entry.documentBytes?.let { out.add(RepoFile("$folder/$basename.jpg", it)) }
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

        // 3. Upload every file as a blob, then hang them all off one tree and one
        //    commit. The contents API (one PUT per file) was simpler but commits
        //    per file, so a receipt landed as three commits — transaction, JSON,
        //    image — and a batch as three per receipt. A PR is a review unit, and
        //    the reviewable change is the receipt, not the file.
        //
        //    Blobs are content-addressed and belong to no branch, so nothing is
        //    visible in the repo until the ref is created in step 4. That is why
        //    the branch is created last: a failure part-way through leaves
        //    unreferenced blobs for GitHub to garbage-collect rather than a
        //    half-populated branch.
        val flattened = pending.flatten()
        val treeEntries = JSONArray()
        flattened.forEachIndexed { position, file ->
            onProgress(
                if (flattened.size == 1) "Uploading the receipt…"
                else "Uploading file ${position + 1} of ${flattened.size}…")
            val blob = api(cfg, "POST", "$repoRoot/git/blobs",
                JSONObject()
                    .put("content", Base64.encodeToString(file.data, Base64.NO_WRAP))
                    .put("encoding", "base64"))
            treeEntries.put(
                JSONObject()
                    .put("path", file.path)
                    // 100644 = a non-executable regular file; the only mode we write.
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha")))
        }

        onProgress("Committing…")
        // `base_tree` takes a *tree* sha, not a commit sha, so resolve the base
        // commit's tree first. Without it the new tree would replace the repo
        // wholesale and every existing file would read as deleted.
        val baseCommit = api(cfg, "GET", "$repoRoot/git/commits/$baseSha")
        val tree = api(cfg, "POST", "$repoRoot/git/trees",
            JSONObject()
                .put("base_tree", baseCommit.getJSONObject("tree").getString("sha"))
                .put("tree", treeEntries))
        val commit = api(cfg, "POST", "$repoRoot/git/commits",
            JSONObject()
                .put("message", commitMessage(filings))
                .put("tree", tree.getString("sha"))
                .put("parents", JSONArray().put(baseSha)))

        // 4. Point a new branch at that commit — the first moment any of this is
        //    reachable in the repo.
        onProgress("Creating the branch…")
        val branch = "beanbeaver/receipt-${branchStamp()}"
        api(cfg, "POST", "$repoRoot/git/refs",
            JSONObject().put("ref", "refs/heads/$branch").put("sha", commit.getString("sha")))

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

    /**
     * Subject for the single commit the whole batch lands as. Mirrors the PR
     * title, with the folders in the body so the commit stands on its own once
     * it's squashed out of the PR context.
     */
    private fun commitMessage(filings: List<Filing>): String =
        "BeanBeaver: ${title(filings).replaceFirstChar { it.lowercase() }}\n\n" +
            filings.joinToString("\n") { "- ${it.folder}/" }

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
