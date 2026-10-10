package com.felipe.apkreceiver

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD

class HttpServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val downloadReceiver = DownloadReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = Prefs.port(this)
        val token = Prefs.token(this)

        try {
            startForeground(
                Notifications.FOREGROUND_ID,
                Notifications.buildForeground(this, "Listening on port $port")
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }

        acquireLocks()
        restartServer(port, token)
        registerMdns(port)

        return START_STICKY
    }

    private fun restartServer(port: Int, token: String) {
        server?.stop()
        server = null
        isRunning = false
        try {
            val newServer = WebServer(port, applicationContext, token)
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            isRunning = true
            Log.i(TAG, "Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server on port $port", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apk-receiver:wake").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "apk-receiver:wifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun registerMdns(port: Int) {
        unregisterMdns()
        try {
            val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "apk-receiver"
                serviceType = "_http._tcp"
                this.port = port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered: ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration error", e)
        }
    }

    private fun unregisterMdns() {
        try {
            nsdListener?.let {
                (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it)
            }
        } catch (_: Exception) {
        }
        nsdListener = null
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {
        }
        unregisterMdns()
        server?.stop()
        server = null
        isRunning = false
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HttpServerService"

        @Volatile
        var isRunning: Boolean = false

        @SuppressLint("StaticFieldLeak")
        @Volatile
        var server: WebServer? = null

        fun start(context: Context) {
            val intent = Intent(context, HttpServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }
}
