package ie.rsa.networkinspector

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal content provider used only to hand the sanitized, already-exported
 * log file to the system share sheet. It never serves any other path on
 * disk: only files inside [exportsDir] are reachable through it.
 */
class LogFileProvider : ContentProvider() {

    companion object {
        private const val EXPORTS_SUBDIR = "exports"

        fun exportsDir(context: android.content.Context): File {
            val dir = File(context.cacheDir, EXPORTS_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun uriForFile(context: android.content.Context, file: File): Uri {
            return Uri.parse("content://ie.rsa.networkinspector.fileprovider/${file.name}")
        }
    }

    override fun onCreate(): Boolean = true

    private fun resolveFile(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains("..") || name.contains('/')) return null
        val ctx = context ?: return null
        val file = File(exportsDir(ctx), name)
        return if (file.exists()) file else null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolveFile(uri) ?: return null
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(arrayOf(file.name, file.length()))
        return cursor
    }

    override fun getType(uri: Uri): String {
        return when {
            uri.lastPathSegment?.endsWith(".json") == true -> "application/json"
            else -> "text/plain"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveFile(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
