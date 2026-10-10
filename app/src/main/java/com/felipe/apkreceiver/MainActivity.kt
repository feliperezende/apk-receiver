package com.felipe.apkreceiver

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.felipe.apkreceiver.databinding.ActivityMainBinding
import com.felipe.apkreceiver.databinding.ItemDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val historyListener: () -> Unit = {
        runOnUiThread { renderHistory() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefs()
        requestNotificationPermissionIfNeeded()
        requestStoragePermissionIfNeeded()
        checkRootAsync()

        DownloadHistory.init(this)
        DownloadHistory.addListener(historyListener)

        binding.saveButton.setOnClickListener { saveAndRestart() }
        binding.copyButton.setOnClickListener { copyUrl() }
        binding.grantButton.setOnClickListener { openInstallPermissionSettings() }

        renderHistory()
        HttpServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshQr()
        updateStatus()
        renderHistory()
    }

    override fun onDestroy() {
        DownloadHistory.removeListener(historyListener)
        super.onDestroy()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        binding.portInput.setText(prefs.getInt(Prefs.KEY_PORT, Prefs.DEFAULT_PORT).toString())
        binding.tokenInput.setText(prefs.getString(Prefs.KEY_TOKEN, "") ?: "")
        binding.autostartSwitch.isChecked = prefs.getBoolean(Prefs.KEY_AUTOSTART, true)
        binding.silentSwitch.isChecked = prefs.getBoolean(Prefs.KEY_SILENT, false)
        binding.silentSwitch.isEnabled = false
    }

    private fun currentPort(): Int =
        binding.portInput.text?.toString()?.trim()?.toIntOrNull() ?: Prefs.DEFAULT_PORT

    private fun currentToken(): String = binding.tokenInput.text?.toString()?.trim() ?: ""

    private fun refreshQr() {
        val url = NetUtils.serverUrl(currentPort(), currentToken())
        binding.urlText.text = url
        try {
            binding.qrImage.setImageBitmap(QrGenerator.generate(url, 640))
        } catch (_: Exception) {
        }
    }

    private fun updateStatus() {
        binding.statusText.text = getString(
            if (HttpServerService.isRunning) R.string.status_running else R.string.status_stopped
        )
    }

    private fun renderHistory() {
        if (!::binding.isInitialized) return
        val container = binding.historyContainer
        container.removeAllViews()
        val entries = DownloadHistory.all()
        binding.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        for (entry in entries) {
            val item = ItemDownloadBinding.inflate(inflater, container, false)
            item.itemName.text = entry.name
            item.itemUrl.text = entry.url
            item.itemTime.text = timeFormat.format(Date(entry.time))
            val (label, colorRes) = statusDisplay(entry.status)
            item.itemStatus.text = label
            item.itemStatus.setTextColor(ContextCompat.getColor(this, colorRes))
            container.addView(item.root)
        }
    }

    private fun statusDisplay(status: String): Pair<String, Int> = when (status) {
        DownloadHistory.STATUS_QUEUED -> "Queued" to R.color.status_pending
        DownloadHistory.STATUS_READY -> "Ready to install" to R.color.status_ok
        DownloadHistory.STATUS_INSTALLED -> "Installed" to R.color.status_ok
        DownloadHistory.STATUS_SUCCESSFUL -> "Downloaded" to R.color.status_ok
        DownloadHistory.STATUS_FAILED -> "Failed" to R.color.status_bad
        else -> status to R.color.muted
    }

    private fun saveAndRestart() {
        val port = currentPort()
        if (port !in 1024..65535) {
            Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
            .putInt(Prefs.KEY_PORT, port)
            .putString(Prefs.KEY_TOKEN, currentToken())
            .putBoolean(Prefs.KEY_AUTOSTART, binding.autostartSwitch.isChecked)
            .putBoolean(Prefs.KEY_SILENT, binding.silentSwitch.isChecked)
            .apply()

        HttpServerService.stop(this)
        HttpServerService.start(this)
        refreshQr()
        binding.statusText.postDelayed({ updateStatus() }, 400)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun copyUrl() {
        val url = NetUtils.serverUrl(currentPort(), currentToken())
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("APK Receiver URL", url))
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("InlinedApi")
    private fun openInstallPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    101
                )
            }
        }
    }

    private fun checkRootAsync() {
        Thread {
            val rooted = InstallHelper.isRooted()
            runOnUiThread {
                binding.silentSwitch.isEnabled = rooted
                if (!rooted) binding.silentSwitch.isChecked = false
            }
        }.start()
    }
}
