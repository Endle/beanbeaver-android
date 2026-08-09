package com.zhenbo.beanbeaver.debug

import android.content.Context
import com.zhenbo.beanbeaver.github.TokenStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A point-in-time snapshot of everything BeanBeaver has written to disk or to
 * SharedPreferences. Backs the debug "Dump All Data" screen — today a developer
 * tool, eventually a user-facing way to verify the "nothing leaves your device,
 * and here's exactly what we keep" promise. Kotlin twin of iOS `DataDump`.
 *
 * Stored **values** are never included for anything sensitive — the encrypted
 * GitHub token is reported by name and size only, and file contents are never
 * read — so the dump itself can't leak a token or a receipt photo.
 */
data class DataDump(
    val preferences: List<Entry>,
    val secrets: List<Entry>,
    val files: List<FileEntry>,
    val generatedAt: Date,
) {
    data class Entry(val key: String, val value: String)

    data class FileEntry(val relativePath: String, val byteCount: Long, val modified: Long)

    companion object {
        /**
         * Every preference BeanBeaver itself writes, kept as an explicit list per
         * store rather than dumping everything — and update this list whenever a
         * new key is introduced elsewhere in the app.
         */
        private val KNOWN_PREFS: Map<String, List<String>> = mapOf(
            "beanbeaver" to listOf(
                "includeDetailsJSON",
                "premiumEnabled",
                "moneyManagerAccount",
                "ledgerCurrency",
                "ledgerTaxAccount",
                "skipOrientationCheck",
                "lastScanWallMs",
                DebugInfoStore.ENABLED_KEY,
            ),
            "beanbeaver_github" to listOf("githubOwner", "githubRepo"),
        )

        fun capture(context: Context): DataDump = DataDump(
            preferences = capturePreferences(context),
            secrets = captureSecrets(context),
            files = captureFiles(context),
            generatedAt = Date(),
        )

        private fun capturePreferences(context: Context): List<Entry> =
            KNOWN_PREFS.flatMap { (store, keys) ->
                val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
                keys.mapNotNull { key ->
                    val value = prefs.all[key] ?: return@mapNotNull null
                    Entry("$store/$key", value.toString())
                }
            }

        /** The Keystore-backed token: reported as present and how big, never read out. */
        private fun captureSecrets(context: Context): List<Entry> =
            if (TokenStore(context).get().isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(Entry("github access token", "<stored, encrypted>"))
            }

        /**
         * Walk every directory the app can write to: filesDir (captures, debug
         * info), cacheDir (generated exports), and the no-backup dir. This is
         * where a captured receipt photo would still be sitting if it were ever
         * left uncleaned — the whole point of this screen is to make that visible
         * rather than assumed.
         */
        private fun captureFiles(context: Context): List<FileEntry> {
            val roots = listOf(
                "files" to context.filesDir,
                "cache" to context.cacheDir,
                "no_backup" to context.noBackupFilesDir,
            )
            val out = mutableListOf<FileEntry>()
            roots.forEach { (label, root) ->
                root?.walkTopDown()?.forEach { file ->
                    if (file.isFile) {
                        val relative = file.absolutePath
                            .removePrefix(root.absolutePath)
                            .trimStart('/')
                        out.add(FileEntry("$label/$relative", file.length(), file.lastModified()))
                    }
                }
            }
            return out.sortedBy { it.relativePath }
        }
    }

    /** Flat text export for sharing off-device, so the dump can be inspected
     *  outside the app too. */
    fun plainText(): String = buildString {
        appendLine("BeanBeaver data dump — ${iso(generatedAt)}")

        appendLine()
        appendLine("== Preferences (${preferences.size}) ==")
        if (preferences.isEmpty()) appendLine("(empty)")
        preferences.forEach { appendLine("${it.key} = ${it.value}") }

        appendLine()
        appendLine("== Secrets (${secrets.size}) ==")
        if (secrets.isEmpty()) appendLine("(empty)")
        secrets.forEach { appendLine("${it.key}: ${it.value}") }

        appendLine()
        appendLine("== Files on disk (${files.size}) ==")
        if (files.isEmpty()) appendLine("(empty)")
        files.forEach { appendLine("${it.relativePath} — ${it.byteCount} B") }

        appendLine()
        appendLine("== Photo library ==")
        appendLine(
            "BeanBeaver only writes here when you use \"Save to Camera Roll\" on a receipt, " +
                "one photo at a time. It can't read the library, so it can't list what it has " +
                "written — check Photos itself.",
        )
    }

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)
}
