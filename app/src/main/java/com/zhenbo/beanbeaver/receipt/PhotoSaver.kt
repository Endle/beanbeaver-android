package com.zhenbo.beanbeaver.receipt

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * Saves a captured receipt image to the user's photo library. Kotlin twin of iOS
 * `PhotoSaver`.
 *
 * Uses MediaStore's scoped-storage insert, which needs **no runtime permission**
 * on the API levels this app supports (minSdk 34) — the app writes only the entry
 * it creates and owns, which is the Android equivalent of iOS's add-only Photos
 * authorization. A failure is logged and swallowed: the scan itself succeeded,
 * and losing the courtesy copy shouldn't surface as a scan error.
 */
object PhotoSaver {
    private const val TAG = "PhotoSaver"

    /** Pictures/BeanBeaver — its own album, so scans don't scatter into the camera roll. */
    private const val ALBUM = "BeanBeaver"

    /** SharedPreferences key behind the "Save a copy to Photos" switch (Settings). */
    const val SAVE_TO_PHOTOS_KEY = "saveScansToPhotos"

    private const val PREFS = "beanbeaver"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SAVE_TO_PHOTOS_KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SAVE_TO_PHOTOS_KEY, value).apply()
    }

    /** Write encoded image bytes (JPEG) into the library. No-op on failure. */
    fun save(context: Context, imageData: ByteArray) {
        val name = "beanbeaver_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            // Marks the entry incomplete so nothing indexes a half-written file;
            // cleared once the bytes are in.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri == null) {
            Log.w(TAG, "couldn't create a MediaStore entry for the scan")
            return
        }

        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(imageData) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure { e ->
            Log.w(TAG, "couldn't write the scan to Photos", e)
            runCatching { resolver.delete(uri, null, null) }
        }
    }
}
