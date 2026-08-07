package com.example.cameratest.network

import android.util.Base64
import com.example.cameratest.data.ProbeProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

/** 原始探测结果（与 UI 解耦） */
data class ProbeOutcome(val reachable: Boolean, val latencyMs: Long, val detail: String)
data class WanOutcome(val reachable: Boolean, val latencyMs: Long, val detail: String, val resolvedIp: String?)

/** 摄像头识别结果：确认某台主机提供可用的 RTSP 流 */
data class CameraIdentity(
    val host: String,
    val rtspPort: Int,
    val rtspPath: String,
    val latencyMs: Long,
    val detail: String
) {
    val rtspUrl: String get() = "rtsp://$host:$rtspPort$rtspPath"
}

/**
 * 扫描到的候选设备（一主机可能开放多个端口）。
 * @param identity 非空表示已确认是摄像头并拿到了可用 RTSP 地址
 */
data class CameraDevice(
    val host: String,
    val openPorts: List<Int>,
    val identity: CameraIdentity?
)

/** 摄像头常用端口（内网扫描时使用） */
val CAMERA_PORTS = listOf(554, 5544, 80, 8080, 81, 8000, 34567, 37777, 88, 6667)

/** CamHipro/V380 等国产摄像头常见的 RTSP 路径，按优先级排序 */
val COMMON_RTSP_PATHS = listOf(
    "/11", "/12", "/13",
    "/live/ch00_0", "/live/ch01_0", "/live/0",
    "/streaming/channels/101", "/streaming/channels/1",
    "/video1", "/video2", "/av0_0", "/av1_0",
    "/onvif/streaming/channels/101",
    "/user=admin_password=tlJWPb65_channel=1_stream=0.sdp",
    "/stream1", "/stream"
)

/** 摄像头识别阶段只探测这些 RTSP 端口（比通用端口列表更精准，避免把 HTTP 等当摄像头） */
val RTSP_PORTS = listOf(554, 5544, 8554, 10554, 8555)

/**
 * 自动识别阶段尝试的路径数量。
 * COMMON_RTSP_PATHS 已按优先级排序，识别时只试前几个高频路径即可，
 * 避免对非摄像头设备逐个尝试 15 个路径拖慢整体识别。
 */
private const val IDENTIFY_PATH_LIMIT = 8

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

/** 完整读取一个 RTSP 响应（直到 \r\n\r\n） */
private fun readRtspResponse(s: Socket, timeoutMs: Int): String {
    s.soTimeout = timeoutMs
    val ins = s.getInputStream()
    val reader = BufferedReader(InputStreamReader(ins))
    val sb = StringBuilder()
    try {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val ch = reader.read()
                if (ch == -1) break
                sb.append(ch.toChar())
                if (sb.endsWith("\r\n\r\n")) break
            } else {
                Thread.sleep(10)
            }
        }
    } catch (_: Exception) {
        // 超时或连接关闭，返回已读到内容
    }
    return sb.toString()
}

private fun buildDigestResponse(
    user: String, password: String,
    realm: String, nonce: String, qop: String?, uri: String
): String {
    val nc = "00000001"
    val cnonce = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
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

/** RTSP OPTIONS 探测：支持 Digest / Basic 鉴权（CamHipro/V380 等国产摄像头常用 Digest） */
suspend fun rtspProbe(
    host: String,
    port: Int,
    path: String,
    user: String = "",
    password: String = "",
    timeoutMs: Int = 4000
): ProbeOutcome = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            val rtspUrl = "rtsp://$host:$port$path"
            val req1 = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: CameraTestApp/1.0\r\n\r\n"
            s.getOutputStream().write(req1.toByteArray())
            s.getOutputStream().flush()
            val resp1 = readRtspResponse(s, timeoutMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val first1 = resp1.lines().firstOrNull() ?: ""
            val headers1 = parseRtspHeaders(resp1)

            if (first1.contains("RTSP/1.0 2")) {
                return@withContext ProbeOutcome(true, elapsed, "RTSP 服务可用 ($first1)")
            }

            val authHeader = headers1["www-authenticate"] ?: ""
            if (first1.contains("RTSP/1.0 4")) {
                if (user.isBlank() || password.isBlank()) {
                    return@withContext ProbeOutcome(false, elapsed, "摄像头需要账号密码（返回 401）")
                }
                if (authHeader.contains("Digest", ignoreCase = true)) {
                    val params = parseDigestParams(authHeader)
                    val realm = params["realm"] ?: ""
                    val nonce = params["nonce"] ?: ""
                    val qop = params["qop"]
                    val auth = buildDigestResponse(user, password, realm, nonce, qop, rtspUrl)
                    // 在同一连接上重试（nonce 通常与会话绑定）
                    val req2 = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: CameraTestApp/1.0\r\nAuthorization: $auth\r\n\r\n"
                    s.getOutputStream().write(req2.toByteArray())
                    s.getOutputStream().flush()
                    val resp2 = readRtspResponse(s, timeoutMs)
                    val first2 = resp2.lines().firstOrNull() ?: ""
                    val elapsed2 = (System.nanoTime() - start) / 1_000_000
                    if (first2.contains("RTSP/1.0 2")) {
                        return@withContext ProbeOutcome(true, elapsed2, "RTSP Digest 鉴权成功 ($first2)")
                    }
                    return@withContext ProbeOutcome(false, elapsed2, "鉴权失败: $first2")
                } else if (authHeader.contains("Basic", ignoreCase = true)) {
                    val auth = "Basic ${basicAuth(user, password)}"
                    val req2 = "OPTIONS $rtspUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: CameraTestApp/1.0\r\nAuthorization: $auth\r\n\r\n"
                    s.getOutputStream().write(req2.toByteArray())
                    s.getOutputStream().flush()
                    val resp2 = readRtspResponse(s, timeoutMs)
                    val first2 = resp2.lines().firstOrNull() ?: ""
                    val elapsed2 = (System.nanoTime() - start) / 1_000_000
                    if (first2.contains("RTSP/1.0 2")) {
                        return@withContext ProbeOutcome(true, elapsed2, "RTSP Basic 鉴权成功 ($first2)")
                    }
                    return@withContext ProbeOutcome(false, elapsed2, "鉴权失败: $first2")
                }
                return@withContext ProbeOutcome(false, elapsed, "鉴权方式不支持: $authHeader")
            }

            return@withContext ProbeOutcome(false, elapsed, "RTSP 响应异常: $first1")
        }
    } catch (e: Exception) {
        ProbeOutcome(false, 0, "RTSP 探测失败: ${e.message}")
    }
}

private fun parseRtspHeaders(resp: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    resp.lines().drop(1).forEach { line ->
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    return headers
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

/**
 * 识别单个主机是否为摄像头。
 * 只对该主机已开放的 RTSP 候选端口做带鉴权的 RTSP OPTIONS 探测，
 * 路径按优先级逐个尝试，命中即返回。全部失败返回 null。
 */
suspend fun identifyCamera(
    host: String,
    openPorts: List<Int>,
    user: String,
    password: String,
    pathTimeoutMs: Int = 3500
): CameraIdentity? {
    val rtspPorts = openPorts.filter { it in RTSP_PORTS }
    if (rtspPorts.isEmpty()) return null
    for (port in rtspPorts) {
        for (path in COMMON_RTSP_PATHS.take(IDENTIFY_PATH_LIMIT)) {
            val r = rtspProbe(host, port, path, user, password, pathTimeoutMs)
            if (r.reachable) {
                return CameraIdentity(host, port, path, r.latencyMs, r.detail)
            }
        }
    }
    return null
}

/**
 * 对一批主机并行识别摄像头。
 * @param hosts 每个元素为主机及其开放端口列表（由扫描阶段汇总）
 * @param onIdentified 每识别出一台摄像头回调（用于实时刷新 UI）
 * @param onProgress 已识别数 / 总主机数
 */
suspend fun identifyCameras(
    hosts: List<Pair<String, List<Int>>>,
    user: String,
    password: String,
    concurrency: Int = 12,
    onIdentified: (CameraIdentity) -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): List<CameraIdentity> = coroutineScope {
    val sem = Semaphore(concurrency)
    var done = 0
    val lock = Any()
    val found = mutableListOf<CameraIdentity>()
    hosts.map { (host, ports) ->
        async {
            sem.withPermit {
                val id = identifyCamera(host, ports, user, password)
                synchronized(lock) {
                    done++
                    onProgress(done, hosts.size)
                    if (id != null) {
                        found.add(id)
                        onIdentified(id)
                    }
                }
            }
        }
    }.awaitAll()
    found
}