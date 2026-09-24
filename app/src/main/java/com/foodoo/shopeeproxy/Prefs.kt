package com.foodoo.shopeeproxy

import android.content.Context

data class ProxyCfg(val host: String, val port: Int, val user: String?, val pass: String?)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun raw(c: Context): String = sp(c).getString("proxy", "") ?: ""
    fun save(c: Context, s: String) = sp(c).edit().putString("proxy", s.trim()).apply()

    /** host:port  hoặc  host:port:user:pass */
    fun parse(s: String): ProxyCfg? {
        val p = s.trim().removePrefix("http://").split(":")
        if (p.size != 2 && p.size != 4) return null
        val port = p[1].toIntOrNull() ?: return null
        if (p[0].isBlank() || port !in 1..65535) return null
        return ProxyCfg(p[0], port, p.getOrNull(2), p.getOrNull(3))
    }
}
