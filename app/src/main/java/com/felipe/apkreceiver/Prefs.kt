package com.felipe.apkreceiver

import android.content.Context

object Prefs {
    const val NAME = "settings"
    const val KEY_PORT = "port"
    const val KEY_TOKEN = "token"
    const val KEY_AUTOSTART = "autostart"
    const val KEY_SILENT = "silent_install"

    const val DEFAULT_PORT = 8080

    fun port(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun token(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""

    fun autostart(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, true)

    fun silentInstall(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SILENT, false)
}
