package com.felipe.apkreceiver

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    fun lanIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("dummy") ||
                    name.startsWith("rmnet") || name.startsWith("p2p")
                ) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue
                        return host
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun serverUrl(port: Int, token: String): String {
        val ip = lanIp() ?: "127.0.0.1"
        val base = "http://$ip:$port/"
        return if (token.isEmpty()) base else "$base?token=$token"
    }
}
