package com.zhenbo.beanbeaver.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a generated file to the system share sheet — the Android analog of iOS's
 * `ActivityView` (UIActivityViewController). The file is vended through the
 * app's [FileProvider] so the receiving app gets a scoped, revocable
 * `content://` grant instead of a raw path.
 */
object ShareFile {
    fun share(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    /** The Office Open XML spreadsheet type Money Manager's importer expects. */
    const val XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
