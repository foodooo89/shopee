package com.foodoo.shopeeproxy

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

class ProxyVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "stop"
        const val TARGET_PKG = "com.shopee.vn"
        @Volatile var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var bridge: Socks2Http? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        val cfg = Prefs.parse(Prefs.raw(this)) ?: run { stopSelf(); return }
        val br = Socks2Http(cfg).also { it.start() }

        // Chỉ TARGET_PKG vào TUN. Route 0/0 + ::/0 để không rò ra ngoài (wifi lẫn 4G).
        val pfd = try {
            Builder()
                .setSession("ShopeeProxy")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addAddress("fc00::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")          // mapdns của hev (fake-ip)
                .addAllowedApplication(TARGET_PKG)
                .establish()
        } catch (e: Exception) { null }

        if (pfd == null) { br.stop(); stopSelf(); return }

        val conf = File(filesDir, "tproxy.yml")
        conf.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              port: ${br.localPort}
              address: 127.0.0.1
              udp: 'udp'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: warn
            """.trimIndent()
        )
        TProxyService.TProxyStartService(conf.absolutePath, pfd.fd)
        tun = pfd
        bridge = br
        running = true
    }

    private fun shutdown() {
        if (!running) return
        running = false
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        bridge?.stop()
        tun = null; bridge = null
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { shutdown(); super.onDestroy() }
}
