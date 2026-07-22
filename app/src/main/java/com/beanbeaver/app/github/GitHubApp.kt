package com.beanbeaver.app.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A user-presentable failure from any GitHub call. Kotlin twin of iOS `FlowError`. */
class GitHubException(message: String) : Exception(message)

/**
 * GitHub **App** Device Flow + the read-side REST calls, the Kotlin twin of iOS
 * `GitHubApp` (`GitHubDeviceFlow.swift`). Lets the user connect their GitHub
 * account by authorizing in the browser — no backend, no embedded secret (device
 * flow needs only the public `client_id`).
 *
 * We reuse the **same GitHub App as iOS** ("beanbeaver-ios"): device flow isn't
 * platform-bound, so the client id / slug work as-is with no extra GitHub setup.
 * Because it's a GitHub App installed on just the ledger repo, the token can only
 * touch that one repo — the least-privilege story for a financial ledger.
 */
object GitHubApp {
    /** Public GitHub App client ID — safe to ship (not a secret). */
    const val CLIENT_ID = "Iv23li8YKsK21kudOvAl"

    /** The app's public slug, for the install URL (github.com/apps/<slug>). */
    const val APP_SLUG = "beanbeaver-ios"

    val isConfigured: Boolean get() = CLIENT_ID.isNotEmpty()

    /** Where to send the user to install the app on their ledger repo. */
    val installUrl: String?
        get() = if (APP_SLUG.isEmpty()) null
        else "https://github.com/apps/$APP_SLUG/installations/new"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        /** GitHub's URL with the code pre-filled — open this so the user only taps "Authorize". */
        val verificationUriComplete: String?,
        val interval: Int,
        val expiresIn: Int,
    )

    /** A repository BeanBeaver is installed on. */
    data class Repo(val owner: String, val name: String) : Comparable<Repo> {
        val fullName: String get() = "$owner/$name"
        override fun compareTo(other: Repo): Int = fullName.compareTo(other.fullName, ignoreCase = true)
    }

    data class RepoAccess(val defaultBranch: String, val canPush: Boolean)

    // MARK: - Step 1: request a device + user code

    suspend fun requestDeviceCode(): DeviceCode {
        if (!isConfigured) throw GitHubException("GitHub sign-in isn't set up in this build.")
        // GitHub Apps take no OAuth `scope`; permissions come from the app + install.
        val json = postForm("https://github.com/login/device/code", "client_id=$CLIENT_ID")
        val deviceCode = json.optString("device_code").ifEmpty {
            throw GitHubException(json.optString("error_description")
                .ifEmpty { "GitHub returned an unexpected device-code response." })
        }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = json.optString("user_code"),
            verificationUri = json.optString("verification_uri"),
            verificationUriComplete = json.optString("verification_uri_complete").ifEmpty { null },
            interval = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900),
        )
    }

    // MARK: - Step 2: poll until the user authorizes

    /**
     * Poll the token endpoint until the user authorizes (or the code expires / is
     * denied). Returns the access token. The app uses non-expiring user tokens, so
     * the response carries only `access_token` (no refresh token to handle).
     */
    suspend fun pollForToken(device: DeviceCode): String {
        var interval = maxOf(device.interval, 1)
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val body = "client_id=$CLIENT_ID&device_code=${device.deviceCode}" +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            val json = postForm("https://github.com/login/oauth/access_token", body)

            json.optString("access_token").ifEmpty { null }?.let { return it }
            when (json.optString("error").ifEmpty { null }) {
                "authorization_pending" -> continue
                "slow_down" -> interval = json.optInt("interval", interval + 5)
                "access_denied" -> throw GitHubException("Authorization was denied.")
                "expired_token" ->
                    throw GitHubException("The code expired before you authorized. Try connecting again.")
                null -> throw GitHubException("Unexpected response from GitHub while waiting for authorization.")
                else -> throw GitHubException(json.optString("error_description").ifEmpty { "GitHub error." })
            }
        }
        throw GitHubException("Timed out waiting for authorization.")
    }

    // MARK: - Step 3: installation + repo access

    /** True if the user has at least one installation of this app. */
    suspend fun hasInstallation(token: String): Boolean {
        val json = getJson("https://api.github.com/user/installations", token)
        return json.optInt("total_count", 0) > 0
    }

    /** Every repo BeanBeaver is installed on, flattened across installations and sorted. */
    suspend fun listInstallationRepos(token: String): List<Repo> {
        val json = getJson("https://api.github.com/user/installations", token)
        val installations = json.optJSONArray("installations")
            ?: throw GitHubException(json.optString("message").ifEmpty { "Couldn't read your installations." })
        val repos = sortedSetOf<Repo>()
        for (i in 0 until installations.length()) {
            val id = installations.getJSONObject(i).optLong("id", -1)
            if (id < 0) continue
            repos.addAll(installationRepos(id, token))
        }
        return repos.toList()
    }

    /** One installation's repos. Paginated: the default page is only 30. */
    private suspend fun installationRepos(id: Long, token: String): List<Repo> {
        val perPage = 100
        val repos = mutableListOf<Repo>()
        var page = 1
        while (true) {
            val json = getJson(
                "https://api.github.com/user/installations/$id/repositories?per_page=$perPage&page=$page",
                token,
            )
            val batch = json.optJSONArray("repositories") ?: break
            for (i in 0 until batch.length()) {
                val entry = batch.getJSONObject(i)
                val name = entry.optString("name")
                val owner = entry.optJSONObject("owner")?.optString("login").orEmpty()
                if (name.isEmpty() || owner.isEmpty()) continue
                repos.add(Repo(owner, name))
            }
            if (batch.length() < perPage) break
            page++
        }
        return repos
    }

    /** The signed-in account's login, used to pre-fill the repo owner. */
    suspend fun fetchLogin(token: String): String {
        val json = getJson("https://api.github.com/user", token)
        return json.optString("login").ifEmpty { throw GitHubException("Couldn't read your GitHub username.") }
    }

    /** Confirm the token can reach `owner/repo` and describe the access. */
    suspend fun checkRepoAccess(owner: String, repo: String, token: String): RepoAccess {
        val json = getJson("https://api.github.com/repos/$owner/$repo", token)
        val defaultBranch = json.optString("default_branch").ifEmpty {
            throw GitHubException(json.optString("message").ifEmpty { "Repository not found or not accessible." })
        }
        val canPush = json.optJSONObject("permissions")?.optBoolean("push", true) ?: true
        return RepoAccess(defaultBranch, canPush)
    }

    // MARK: - Transport
    //
    // Like iOS `GitHubApp`, these don't validate the HTTP status: the device-flow
    // endpoints return 200 with an `error` field the caller inspects, and API
    // errors carry a `message` in the JSON. Only a transport failure is wrapped.

    private suspend fun postForm(urlString: String, body: String): JSONObject =
        withContext(Dispatchers.IO) { request("POST", urlString, formBody = body, token = null) }

    private suspend fun getJson(urlString: String, token: String): JSONObject =
        withContext(Dispatchers.IO) { request("GET", urlString, formBody = null, token = token) }

    private fun request(method: String, urlString: String, formBody: String?, token: String?): JSONObject {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        val text: String = try {
            conn.requestMethod = method
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            } else {
                conn.setRequestProperty("Accept", "application/json")
            }
            if (formBody != null) {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.outputStream.use { it.write(formBody.toByteArray()) }
            }
            val ok = conn.responseCode in 200..299
            val stream = if (ok) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            stream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            val path = runCatching { URL(urlString).path }.getOrDefault("?")
            throw GitHubException("GitHub $method $path failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw GitHubException("Couldn't read GitHub's response.")
        }
    }
}
