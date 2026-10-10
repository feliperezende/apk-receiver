package com.felipe.apkreceiver

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebServer(
    port: Int,
    private val context: Context,
    private val token: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/" || session.uri == "/index.html" -> serveIndex()
                session.uri == "/ping" -> json(
                    Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put("app", "apk-receiver")
                        .put("version", BuildConfig.VERSION_NAME)
                )
                session.uri == "/fetch" ->
                    if (session.method == Method.POST) handleFetch(session)
                    else error(Response.Status.METHOD_NOT_ALLOWED, "POST only")
                session.uri == "/status" -> handleStatus(session)
                session.uri == "/history" -> handleHistory()
                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun serveIndex(): Response {
        val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun error(status: Response.Status, message: String): Response =
        json(status, JSONObject().put("error", message))

    private fun authorized(session: IHTTPSession, bodyToken: String?): Boolean {
        if (token.isEmpty()) return true
        val header = session.headers["x-auth-token"]
        val query = session.parameters["token"]?.firstOrNull()
        return token == header || token == bodyToken || token == query
    }

    private fun handleFetch(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        var url: String?
        var name: String?
        var bodyToken: String? = null
        try {
            val obj = JSONObject(body)
            url = obj.optString("url").ifBlank { null }
            name = obj.optString("name").ifBlank { null }
            bodyToken = obj.optString("token").ifBlank { null }
        } catch (_: Exception) {
            url = session.parameters["url"]?.firstOrNull()
            name = session.parameters["name"]?.firstOrNull()
        }

        if (!authorized(session, bodyToken)) {
            return error(Response.Status.UNAUTHORIZED, "invalid token")
        }
        if (url.isNullOrBlank()) {
            return error(Response.Status.BAD_REQUEST, "missing url")
        }

        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return error(Response.Status.BAD_REQUEST, "only http/https URLs are allowed")
        }

        val fileName = uniqueFileName(sanitize(name ?: guessName(url)))
        val id = enqueue(url, fileName)
        if (id < 0) {
            return error(Response.Status.INTERNAL_ERROR, "could not enqueue download")
        }

        val entry = JSONObject()
            .put("id", id)
            .put("name", fileName)
            .put("url", url)
        DownloadHistory.add(context, id, fileName, url)

        return json(Response.Status.OK, entry)
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return error(Response.Status.BAD_REQUEST, "missing id")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return error(Response.Status.NOT_FOUND, "unknown id")
        }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        cursor.close()

        return json(
            Response.Status.OK,
            JSONObject()
                .put("id", id)
                .put("statusCode", status)
                .put("status", statusName(status))
                .put("bytes", bytes)
                .put("total", total)
                .put("uri", uri ?: "")
        )
    }

    private fun handleHistory(): Response {
        val array = JSONArray()
        for (entry in DownloadHistory.all()) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("url", entry.url)
                    .put("time", entry.time)
                    .put("status", entry.status)
            )
        }
        return json(Response.Status.OK, JSONObject().put("items", array))
    }

    private fun enqueue(url: String, fileName: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("APK download")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.e("WebServer", "enqueue failed for $url -> $fileName", e)
            -1L
        }
    }

    private fun guessName(url: String): String {
        val path = Uri.parse(url).lastPathSegment ?: ""
        var name = path.substringAfterLast('/').substringBefore('?')
        if (name.isBlank()) name = "download.apk"
        if (!name.contains('.')) name = "$name.apk"
        return name
    }

    private fun sanitize(name: String): String {
        val base = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "download.apk" }
        return if (base.lowercase().endsWith(".apk")) base else "$base.apk"
    }

    private fun uniqueFileName(name: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!File(dir, name).exists()) return name
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val stem = name.removeSuffix(".apk").removeSuffix(".APK")
        return "${stem}_$stamp.apk"
    }

    private fun statusName(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "pending"
        DownloadManager.STATUS_RUNNING -> "running"
        DownloadManager.STATUS_PAUSED -> "paused"
        DownloadManager.STATUS_SUCCESSFUL -> "successful"
        DownloadManager.STATUS_FAILED -> "failed"
        else -> "unknown"
    }
}
