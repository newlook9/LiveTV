package com.livetv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("livetv", Context.MODE_PRIVATE)

    fun getString(k: String, d: String): String = sp.getString(k, d) ?: d
    fun putString(k: String, v: String) {
        sp.edit().putString(k, v).apply()
    }
    fun getInt(k: String, d: Int): Int = sp.getInt(k, d)
    fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
    }

    private fun toJson(list: List<Channel>): JSONArray {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("n", c.name)
            o.put("u", c.url)
            arr.put(o)
        }
        return arr
    }

    fun getList(key: String): MutableList<Channel> {
        val out = ArrayList<Channel>()
        try {
            val arr = JSONArray(getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Channel(o.getString("n"), o.getString("u")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveList(key: String, list: List<Channel>) {
        putString(key, toJson(list).toString())
    }

    fun getPlaylists(): MutableList<Playlist> {
        val out = ArrayList<Playlist>()
        try {
            val arr = JSONArray(getString("playlists", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = ArrayList<Channel>()
                val ia = o.getJSONArray("items")
                for (j in 0 until ia.length()) {
                    val c = ia.getJSONObject(j)
                    items.add(Channel(c.getString("n"), c.getString("u")))
                }
                out.add(Playlist(o.getString("name"), items))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("items", toJson(p.items))
            arr.put(o)
        }
        putString("playlists", arr.toString())
    }

    private fun cacheFile(key: String): File =
        File(ctx.filesDir, "list_" + key.replace("/", "_") + ".txt")

    fun readCache(key: String): String? {
        return try {
            val f = cacheFile(key)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    fun writeCache(key: String, text: String) {
        try {
            cacheFile(key).writeText(text)
        } catch (e: Exception) {
        }
    }

    fun download(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "LiveTV/1.0 (Android)")
            if (c.responseCode != 200) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}

fun parseM3u(text: String): List<Channel> {
    val out = ArrayList<Channel>()
    var name: String? = null
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
        } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
            out.add(Channel(name, line))
            name = null
        }
    }
    return out
}

fun parseRadio(text: String): List<Channel> {
    val out = ArrayList<Channel>()
    try {
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name").trim()
            val u = o.optString("url_resolved").trim()
            if (n.isNotEmpty() && u.isNotEmpty()) out.add(Channel(n, u))
        }
    } catch (e: Exception) {
    }
    return out
}
