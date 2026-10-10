package com.felipe.apkreceiver

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object InstallHelper {

    private const val TAG = "InstallHelper"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun installFromDownload(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return
        }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        val mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
        cursor.close()

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Log.w(TAG, "Download $downloadId not successful (status=$status)")
            DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_FAILED)
            return
        }

        val path = Uri.parse(localUri ?: "").path ?: return
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Downloaded file missing: $path")
            DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_FAILED)
            return
        }
        val isApk = mime == APK_MIME || file.name.lowercase().endsWith(".apk")
        if (!isApk) {
            Log.i(TAG, "Downloaded file is not an APK, skipping install: ${file.name}")
            DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_SUCCESSFUL)
            return
        }

        if (Prefs.silentInstall(context) && isRooted()) {
            val ok = runSu("pm install -r \"${file.absolutePath}\"")
            Notifications.installed(context, title ?: file.name, ok)
            if (ok) {
                DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_INSTALLED)
                return
            }
            Log.w(TAG, "Silent install failed, falling back to dialog")
        }

        val contentUri = dm.getUriForDownloadedFile(downloadId) ?: fileUri(context, file)
        if (contentUri == null) {
            Notifications.error(context, "Could not open installer for ${file.name}")
            DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_SUCCESSFUL)
            return
        }
        DownloadHistory.updateStatus(context, downloadId, DownloadHistory.STATUS_READY)
        launchInstaller(context, contentUri)
    }

    private fun fileUri(context: Context, file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        Log.e(TAG, "FileProvider failed for ${file.absolutePath}", e)
        null
    }

    private fun launchInstaller(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            Notifications.error(context, "Could not open installer: ${e.message}")
        }
    }

    fun isRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun runSu(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
