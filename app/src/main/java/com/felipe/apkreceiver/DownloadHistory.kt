package com.felipe.apkreceiver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

object DownloadHistory {

    data class Entry(
        val id: Long,
        val name: String,
        val url: String,
        val time: Long,
        var status: String
    )

    const val STATUS_QUEUED = "queued"
    const val STATUS_SUCCESSFUL = "successful"
    const val STATUS_FAILED = "failed"
    const val STATUS_INSTALLED = "installed"
    const val STATUS_READY = "ready"

    private const val PREFS = "download_history"
    private const val KEY = "items"
    private const val MAX = 50

    private val entries = Collections.synchronizedList(mutableListOf<Entry>())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        load(context.applicationContext)
        loaded = true
    }

    fun add(context: Context, id: Long, name: String, url: String) {
        init(context)
        entries.add(0, Entry(id, name, url, System.currentTimeMillis(), STATUS_QUEUED))
        trim()
        persist(context)
        notifyChanged()
    }

    fun updateStatus(context: Context, id: Long, status: String) {
        init(context)
        val entry = synchronized(entries) { entries.firstOrNull { it.id == id } } ?: return
        if (entry.status == status) return
        entry.status = status
        persist(context)
        notifyChanged()
    }

    fun all(): List<Entry> = synchronized(entries) { entries.toList() }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Exception) {
            }
        }
    }

    private fun trim() {
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        synchronized(entries) {
            for (entry in entries) {
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("name", entry.name)
                        .put("url", entry.url)
                        .put("time", entry.time)
                        .put("status", entry.status)
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    private fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        try {
            val array = JSONArray(raw)
            synchronized(entries) {
                entries.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    entries.add(
                        Entry(
                            obj.optLong("id"),
                            obj.optString("name"),
                            obj.optString("url"),
                            obj.optLong("time"),
                            obj.optString("status", STATUS_QUEUED)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
}
