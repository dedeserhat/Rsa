package ie.rsa.networkinspector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object LogExporter {

    fun writeTxt(context: Context, entries: List<LogEntry>): File {
        val file = newExportFile(context, "txt")
        FileOutputStream(file).use { out ->
            out.write("RSA Network Inspector - sanitized log export\n".toByteArray())
            out.write("Generated: ${DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())}\n".toByteArray())
            out.write("This export contains no passwords, cookies, authorization headers, or tokens.\n\n".toByteArray())
            entries.forEach { entry ->
                entry.exportLines().forEach { line -> out.write((line + "\n").toByteArray()) }
                out.write("\n".toByteArray())
            }
        }
        return file
    }

    fun writeJson(
        context: Context,
        entries: List<LogEntry>,
        availabilityEntries: List<AvailabilityResponseEntry> = emptyList()
    ): File {
        val file = newExportFile(context, "json")
        val root = JSONObject()
        root.put("app", "RSA Network Inspector")
        root.put("generatedAt", System.currentTimeMillis())
        root.put("note", "Sanitized export: no passwords, cookies, authorization headers, or tokens are recorded.")
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("timestamp", entry.timestamp)
            obj.put("time", entry.formattedTime())
            obj.put("source", entry.source.name)
            obj.put("method", entry.method ?: JSONObject.NULL)
            obj.put("url", entry.url)
            obj.put("host", entry.host)
            obj.put("path", entry.path)
            obj.put("resourceType", entry.resourceKind.name)
            obj.put("isMainFrame", entry.isMainFrame)
            obj.put("isRedirect", entry.isRedirect)
            obj.put("flagged", entry.isFlagged)
            val q = JSONObject()
            entry.sanitizedQuery.forEach { (k, v) -> q.put(k, v) }
            obj.put("query", q)
            array.put(obj)
        }
        root.put("entries", array)

        val availArray = JSONArray()
        availabilityEntries.forEach { a ->
            val obj = JSONObject()
            obj.put("timestamp", a.timestamp)
            obj.put("time", a.formattedTime())
            obj.put("method", a.method)
            obj.put("sanitizedUrl", a.sanitizedUrl)
            obj.put("status", a.status)
            obj.put("contentType", a.contentType)
            obj.put("responseBody", a.prettyBody)
            obj.put("slotCount", a.slotCount ?: JSONObject.NULL)
            obj.put("detectedFields", JSONArray(a.detectedFields))
            obj.put("timerValueSeconds", a.timerValueSeconds ?: JSONObject.NULL)
            availArray.put(obj)
        }
        root.put("availabilityResponses", availArray)

        FileOutputStream(file).use { out -> out.write(root.toString(2).toByteArray()) }
        return file
    }

    fun writeDebugLog(context: Context, debugSummary: String): File {
        val file = newExportFile(context, "txt", prefix = "rsa-slot-watcher-debug")
        FileOutputStream(file).use { out ->
            out.write("RSA Slot Watcher - sanitized debug export\n".toByteArray())
            out.write("Generated: ${DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())}\n".toByteArray())
            out.write("No cookies, tokens, or authorization headers are ever recorded.\n\n".toByteArray())
            out.write(debugSummary.toByteArray())
        }
        return file
    }

    private fun newExportFile(context: Context, extension: String, prefix: String = "rsa-network-log"): File {
        val dir = LogFileProvider.exportsDir(context)
        val name = "$prefix-${System.currentTimeMillis()}.$extension"
        return File(dir, name)
    }

    fun shareIntentFor(context: Context, file: File): Intent {
        val uri: Uri = LogFileProvider.uriForFile(context, file)
        val mime = if (file.extension == "json") "application/json" else "text/plain"
        val send = Intent(Intent.ACTION_SEND)
        send.type = mime
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export RSA Network Inspector log")
    }

    fun plainTextFor(entries: List<LogEntry>): String {
        val sb = StringBuilder()
        entries.forEach { entry -> entry.exportLines().forEach { sb.append(it).append('\n') } }
        return sb.toString()
    }
}
