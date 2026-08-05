package com.example.cameratest.network

import android.util.Base64
import com.example.cameratest.data.ProbeProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** 原始探测结果（与 UI 解耦） */
data class ProbeOutcome(val reachable: Boolean, val latencyMs: Long, val detail: String)
data class WanOutcome(val reachable: Boolean, val latencyMs: Long, val detail: String, val resolvedIp: String?)

/** 摄像头常用端口（内网扫描时使用） */
val CAMERA_PORTS = listOf(554, 5544, 80, 8080, 81, 8000, 34567, 37777, 88, 6667)

private fun basicAuth(user: String, password: String): String =
    Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

/** TCP 端口连通性 + 延迟探测 */
suspend fun tcpProbe(host: String, port: Int, timeoutMs: Int = 2000): ProbeOutcome =
    withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                ProbeOutcome(true, elapsed, "TCP 连接成功")
            }
        } catch (e: Exception) {
            ProbeOutcome(false, 0, e.message ?: "连接失败")
        }
    }

/** RTSP OPTIONS 探测：发送 OPTIONS 并读取首行判断服务是否可用 */
suspend fun rtspProbe(
    host: String,
    port: Int,
    path: String,
    user: String = "",
    password: String = "",
    timeoutMs: Int = 3000
): ProbeOutcome = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val rtspUrl = "rtsp://$host:$port$path"
            val auth = if (user.isNotEmpty() || password.isNotEmpty())
                "Authorization: Basic ${basicAuth(user, password)}\r\n" else ""
            val req = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 1\r\n$auth\r\n\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(1024)
            val n = s.getInputStream().read(buf)
            val resp = if (n > 0) String(buf, 0, n) else ""
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val firstLine = resp.lines().firstOrNull() ?: ""
            val ok = firstLine.contains("RTSP/1.0 2")
            ProbeOutcome(
                ok,
                elapsed,
                if (ok) "RTSP 服务可用 ($firstLine)" else "RTSP 响应异常: ${resp.take(80).replace("\n", " ")}"
            )
        }
    } catch (e: Exception) {
        ProbeOutcome(false, 0, e.message ?: "RTSP 探测失败")
    }
}

/** 外网探测：先 DNS 解析，再 TCP 探测（验证 DDNS / 端口转发是否生效） */
suspend fun wanProbe(host: String, port: Int, timeoutMs: Int = 4000): WanOutcome =
    withContext(Dispatchers.IO) {
        val resolved = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        if (resolved == null) {
            return@withContext WanOutcome(false, 0, "DNS 解析失败: $host", null)
        }
        val tcp = tcpProbe(resolved, port, timeoutMs)
        WanOutcome(
            tcp.reachable,
            tcp.latencyMs,
            if (tcp.reachable) "外网可达（端口转发 / DDNS 生效）" else "外网不可达: ${tcp.detail}",
            resolved
        )
    }

/**
 * 内网子网扫描：并发探测每个主机上的摄像头常用端口。
 * @param onProgress 已探测数 / 总数
 * @param onHit 命中开放端口时回调（host, port）
 */
suspend fun scanLan(
    base24: String,
    ports: List<Int> = CAMERA_PORTS,
    timeoutMs: Int = 600,
    concurrency: Int = 64,
    onProgress: (done: Int, total: Int) -> Unit,
    onHit: (host: String, port: Int) -> Unit
): Unit = coroutineScope {
    val hosts = NetworkUtils.buildSubnetAddresses(base24)
    val total = hosts.size * ports.size
    val sem = Semaphore(concurrency)
    var done = 0
    val lock = Any()
    hosts.flatMap { host ->
        ports.map { port ->
            async {
                sem.withPermit {
                    val r = tcpProbe(host, port, timeoutMs)
                    synchronized(lock) {
                        done++
                        onProgress(done, total)
                    }
                    if (r.reachable) onHit(host, port)
                }
            }
        }
    }.awaitAll()
}

/** 按协议选择探测方式，返回统一结果 */
suspend fun probeByProtocol(
    host: String,
    port: Int,
    protocol: ProbeProtocol,
    path: String,
    user: String,
    password: String
): ProbeOutcome = when (protocol) {
    ProbeProtocol.TCP -> tcpProbe(host, port)
    ProbeProtocol.RTSP -> rtspProbe(host, port, path, user, password)
    ProbeProtocol.HTTP -> tcpProbe(host, port)
    ProbeProtocol.MJPEG -> tcpProbe(host, port)
}
