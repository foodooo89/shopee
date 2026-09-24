package com.foodoo.shopeeproxy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var btn: Button
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 3000) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        input = EditText(this).apply {
            hint = "host:port  (hoặc host:port:user:pass)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.raw(this@MainActivity))
        }
        btn = Button(this).apply { setOnClickListener { toggle() } }
        status = TextView(this).apply { setPadding(0, pad, 0, pad); textSize = 16f }
        val vpnSettings = Button(this).apply {
            text = "Cài Always-on + Block without VPN"
            setOnClickListener { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        }
        listOf(input, btn, status, vpnSettings).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun toggle() {
        if (ProxyVpnService.running) {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            ui.postDelayed({ refresh() }, 500)
            return
        }
        if (Prefs.parse(input.text.toString()) == null) {
            Toast.makeText(this, "Proxy sai định dạng", Toast.LENGTH_SHORT).show(); return
        }
        Prefs.save(this, input.text.toString())
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else launch()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK) launch()
    }

    private fun launch() {
        startService(Intent(this, ProxyVpnService::class.java))
        ui.postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        val on = ProxyVpnService.running
        btn.text = if (on) "TẮT" else "BẬT"
        val cfg = Prefs.parse(input.text.toString())
        Thread {
            val ok = cfg != null && try {
                Socks2Http.openTunnel(cfg, "www.google.com:443").close(); true
            } catch (e: Exception) { false }
            runOnUiThread {
                status.text = "VPN: ${if (on) "ON" else "OFF"}\n" +
                    "Proxy: ${if (ok) "OK" else "DIE / không kết nối"}\n" +
                    when {
                        !on -> "⚠ Shopee đang đi thẳng mạng (không qua proxy)"
                        !ok -> "⛔ Shopee đang bị chặn hoàn toàn"
                        else -> "✔ Shopee đi qua proxy"
                    }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }
}
