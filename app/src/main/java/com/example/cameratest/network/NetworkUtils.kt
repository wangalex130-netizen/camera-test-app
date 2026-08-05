package com.example.cameratest.network

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网信息工具：获取本机 IPv4、推导 /24 网段、生成待扫描地址列表。
 * 使用 NetworkInterface 枚举，避免对 WifiManager 的权限依赖。
 */
object NetworkUtils {

    /** 返回本机第一个非回环 IPv4 地址（优先无线/以太网） */
    fun getLocalIpV4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** 由 IPv4 推导 /24 网段基址，如 192.168.1.60 -> 192.168.1.0 */
    fun subnetBase24(localIp: String): String {
        val parts = localIp.split('.')
        return "${parts[0]}.${parts[1]}.${parts[2]}.0"
    }

    /** 生成 1..254 的主机地址列表（不含 .0 与 .255） */
    fun buildSubnetAddresses(base24: String): List<String> {
        val prefix = base24.removeSuffix(".0")
        return (1..254).map { "$prefix.$it" }
    }
}
