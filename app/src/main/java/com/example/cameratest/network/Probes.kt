package com.example.cameratest.network

import android.util.Base64
import com.example.cameratest.data.ProbeProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

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

private fun md5Hex(s: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun parseDigestParams(header: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val idx = header.indexOf("Digest")
    val body = if (idx >= 0) header.substring(idx + 6) else header
    val regex = "(\\w+)=[\"']?([^\"',\\s]+)[\"']?".toRegex()
    regex.findAll(body).forEach { m ->
        map[m.groupValues[1].lowercase()] = m.groupValues[2].trim('"', '\'')
    }
    return map
}

/** RTSP OPTIONS 探测：支持 Digest 鉴权（CamHipro/V380 等国产摄像头常用） */
suspend fun rtspProbe(
    host: String,
    port: Int,
    path: String,
    user: String = "",
    password: String = "",
    timeoutMs: Int = 4000
): ProbeOutcome = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    fun readResp(s: Socket): Pair<String, Map<String, String>> {
        s.soTimeout = timeoutMs
        val buf = ByteArray(2048)
        val n = s.getInputStream().read(buf)
        val resp = if (n > 0) String(buf, 0, n) else ""
        val headers = mutableMapOf<String, String>()
        resp.lines().drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return resp to headers
    }
    fun buildAuthResponse(realm: String, nonce: String, qop: String?, uri: String): String {
        val nc = "00000001"
        val cnonce = Random.nextBytes(4).joinToString("") { "%02x".format(it) }
        val a1 = md5Hex("$user:$realm:$password")
        val a2 = md5Hex("OPTIONS:$uri")
        val response = if (!qop.isNullOrEmpty()) {
            md5Hex("$a1:$nonce:$nc:$cnonce:$qop:$a2")
        } else {
            md5Hex("$a1:$nonce:$a2")
        }
        val sb = StringBuilder("Digest ")
        sb.append("username=\"$user\", ")
        sb.append("realm=\"$realm\", ")
        sb.append("nonce=\"$nonce\", ")
        sb.append("uri=\"$uri\", ")
        if (!qop.isNullOrEmpty()) {
            sb.append("qop=$qop, ")
            sb.append("nc=$nc, ")
            sb.append("cnonce=\"$cnonce\", ")
        }
        sb.append("response=\"$response\"")
        return sb.toString()
    }
    try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            val rtspUrl = "rtsp://$host:$port$path"
            val req1 = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: CameraTestApp/1.0\r\n\r\n"
            s.getOutputStream().write(req1.toByteArray())
            s.getOutputStream().flush()
            val (resp1, headers1) = readResp(s)
            val first1 = resp1.lines().firstOrNull() ?: ""
            val elapsed = (System.nanoTime() - start) / 1_000_000

            if (first1.contains("RTSP/1.0 2")) {
                return@withContext ProbeOutcome(true, elapsed, "RTSP 服务可用 ($first1)")
            }

            val authHeader = headers1["www-authenticate"] ?: ""
            if (first1.contains("RTSP/1.0 4") && authHeader.contains("Digest", ignoreCase = true)) {
                if (user.isBlank() || password.isBlank()) {
                    return@withContext ProbeOutcome(false, elapsed, "摄像头需要账号密码（返回 401），请填写")
                }
                val params = parseDigestParams(authHeader)
                val realm = params["realm"] ?: ""
                val nonce = params["nonce"] ?: ""
                val qop = params["qop"]
                val auth = buildAuthResponse(realm, nonce, qop, rtspUrl)
                val req2 = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: CameraTestApp/1.0\r\nAuthorization: $auth\r\n\r\n"
                Socket().use { s2 ->
                    s2.connect(InetSocketAddress(host, port), timeoutMs)
                    s2.getOutputStream().write(req2.toByteArray())
                    s2.getOutputStream().flush()
                    val (resp2, _) = readResp(s2)
                    val first2 = resp2.lines().firstOrNull() ?: ""
                    val elapsed2 = (System.nanoTime() - start) / 1_000_000
                    if (first2.contains("RTSP/1.0 2")) {
                        return@withContext ProbeOutcome(true, elapsed2, "RTSP Digest 鉴权成功 ($first2)")
                    }
                    return@withContext ProbeOutcome(false, elapsed2, "鉴权失败: $first2")
                }
            }

            return@withContext ProbeOutcome(false, elapsed, "RTSP 响应异常: ${resp1.take(120).replace("\n", " ")}")
        }
    } catch (e: Exception) {
        ProbeOutcome(false, 0, "RTSP 探测失败: ${e.message}")
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
