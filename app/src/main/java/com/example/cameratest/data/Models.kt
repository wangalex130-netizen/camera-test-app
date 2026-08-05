package com.example.cameratest.data

/** 网络范围：内网 or 外网 */
enum class NetScope { LAN, WAN }

/** 探测协议 */
enum class ProbeProtocol(val label: String) {
    TCP("TCP 端口"),
    RTSP("RTSP 流"),
    HTTP("HTTP 网页"),
    MJPEG("MJPEG 流")
}

/** 手动探测目标 */
data class ProbeTarget(
    val host: String,
    val port: Int,
    val protocol: ProbeProtocol,
    val path: String = "",
    val user: String = "",
    val password: String = ""
)

/** 一次探测的结果记录（用于历史） */
data class TestResult(
    val id: Long,
    val scope: NetScope,
    val target: String,      // host:port 或完整地址
    val protocol: ProbeProtocol,
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
