package com.foodoo.shopeeproxy

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * SOCKS5 server cục bộ (chỉ TCP CONNECT) -> đẩy sang HTTP proxy bằng CONNECT.
 * Proxy die / từ chối => đóng kết nối => Shopee mất mạng hoàn toàn (kill switch).
 * UDP ASSOCIATE bị từ chối => QUIC/UDP bị chặn, app tự fallback TCP.
 */
class Socks2Http(private val cfg: ProxyCfg) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    val localPort: Int get() = server!!.localPort

    fun start() {
        val s = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (true) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            val i = c.getInputStream()
            val o = c.getOutputStream()
            if (i.read() != 5) return
            readN(i, i.read())              // bỏ qua danh sách method
            o.write(byteArrayOf(5, 0))      // no-auth
            val h = readN(i, 4)             // ver cmd rsv atyp
            if (h[1].toInt() != 1) { o.write(reply(7)); return }
            val dest = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(readN(i, 4)).hostAddress
                3 -> String(readN(i, i.read()))
                4 -> InetAddress.getByAddress(readN(i, 16)).hostAddress
                else -> { o.write(reply(8)); return }
            }!!
            val pb = readN(i, 2)
            val port = ((pb[0].toInt() and 255) shl 8) or (pb[1].toInt() and 255)
            val authority = if (dest.contains(':')) "[$dest]:$port" else "$dest:$port"

            up = try { openTunnel(cfg, authority) } catch (e: Exception) { o.write(reply(5)); return }
            o.write(reply(0))

            val u: Socket = up!!
            val t = pool.submit { pump(u, c) }
            pump(c, u)
            t.get()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
            try { up?.close() } catch (_: Exception) {}
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream(), 16384)
            try { to.shutdownOutput() } catch (_: Exception) {}
        } catch (_: Exception) {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }

    private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val k = i.read(b, r, n - r)
            if (k < 0) throw IOException("eof")
            r += k
        }
        return b
    }

    companion object {
        /** Mở tunnel HTTP CONNECT tới authority (host:port) qua proxy. Ném exception nếu proxy die/từ chối. */
        fun openTunnel(cfg: ProxyCfg, authority: String): Socket {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(cfg.host, cfg.port), 5000)
                s.soTimeout = 8000
                val auth = if (cfg.user != null)
                    "Proxy-Authorization: Basic " +
                        Base64.encodeToString("${cfg.user}:${cfg.pass}".toByteArray(), Base64.NO_WRAP) + "\r\n"
                else ""
                s.getOutputStream().write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n$auth\r\n".toByteArray())
                val i = s.getInputStream()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) {
                    val b = i.read()
                    if (b < 0 || sb.length > 8192) throw IOException("proxy eof")
                    sb.append(b.toChar())
                }
                if (sb.split(" ").getOrNull(1) != "200") throw IOException("proxy refused")
                s.soTimeout = 0
                return s
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}
