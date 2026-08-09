package com.zhenbo.beanbeaver.receipt

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves a receipt photo to the user's photo library. Kotlin twin of iOS
 * `PhotoSaver`.
 *
 * Every save is something the user asked for by name (Receipts → a receipt →
 * "Save to Camera Roll"), so this reports back rather than failing silently.
 * It used to be the opposite: a "Save a copy to Photos" switch that copied
 * every camera scan, unconditionally and invisibly, into a place none of the
 * app's own delete controls can reach. Once the app kept a real list of
 * receipts to point at, a per-receipt action was the right shape.
 *
 * Uses MediaStore's scoped-storage insert, which needs **no runtime permission**
 * on the API levels this app supports (minSdk 34) — the app writes only the
 * entry it creates and owns, and can't read the library back. That is the
 * narrowest thing that can do this, and the Android equivalent of iOS's
 * add-only Photos authorization; iOS's `notAuthorized` case has no twin here
 * because there is nothing to authorize.
 */
object PhotoSaver {

    /** Pictures/BeanBeaver — its own album, so scans don't scatter into the camera roll. */
    private const val ALBUM = "BeanBeaver"

    /**
     * Why a save didn't end with the image in the library. Typed rather than a
     * bare message so the caller shows the user's own words back to them: the
     * two ways this goes wrong look identical from the outside otherwise.
     */
    sealed class Failure(message: String) : Exception(message) {
        /** The receipt's photo was cleared, or the file is gone from this device. */
        data object ReadFailed :
            Failure("This receipt's photo is no longer on this device.")

        /** MediaStore refused the entry or the write failed part-way. */
        class WriteFailed(detail: String) :
            Failure("BeanBeaver couldn't add this photo to your library. $detail")
    }

    /** Copy the photo at [file] into the library. Throws [Failure] on every other path. */
    suspend fun save(context: Context, file: File) {
        val data = withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrNull()
        } ?: throw Failure.ReadFailed
        save(context, data)
    }

    /** Write encoded image bytes (JPEG) into the library. Throws [Failure] if they don't land. */
    suspend fun save(context: Context, imageData: ByteArray): Unit = withContext(Dispatchers.IO) {
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
        }.getOrNull() ?: throw Failure.WriteFailed("Your photo library wouldn't accept a new entry.")

        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(imageData) }
                ?: error("couldn't open the new entry for writing")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure { e ->
            // Leave nothing half-written behind — the entry is still pending, so
            // nothing has indexed it, and dropping it keeps a failed save from
            // showing up as an empty photo.
            runCatching { resolver.delete(uri, null, null) }
            throw Failure.WriteFailed(e.message ?: e.toString())
        }
    }
}
