package com.example.cameratest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameratest.data.NetScope
import com.example.cameratest.data.ProbeProtocol
import com.example.cameratest.data.TestResult
import com.example.cameratest.network.CAMERA_PORTS
import com.example.cameratest.network.CameraDevice
import com.example.cameratest.network.CameraIdentity
import com.example.cameratest.network.NetworkUtils
import com.example.cameratest.network.identifyCameras
import com.example.cameratest.network.scanLan
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.ScopeBadge
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.components.StatusDot
import com.example.cameratest.ui.theme.Green
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(vm: AppViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var localIp by remember { mutableStateOf(NetworkUtils.getLocalIpV4() ?: "未获取到") }
    var subnet by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var identifyProgress by remember { mutableStateOf(0 to 0) }
    var devices by remember { mutableStateOf<List<CameraDevice>>(emptyList()) }
    var customSubnet by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("abc123456") }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (localIp != "未获取到") subnet = NetworkUtils.subnetBase24(localIp)
    }

    val busy = scanning || identifying

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SectionCard(title = "本机网络") {
            Text("本地 IP：$localIp", style = MaterialTheme.typography.bodyMedium)
            Text(
                "网段 /24：${subnet.ifEmpty { "—" }}${if (subnet.isEmpty()) "" else ".0/24"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            Spacer(Modifier.height(8.dp))
            LabeledTextField(
                label = "自定义网段（可选，留空用本机网段，如 192.168.1.0）",
                value = customSubnet,
                onValueChange = { customSubnet = it.trim() },
                placeholder = "192.168.1.0"
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "摄像头识别") {
            Text(
                "扫描后会自动用 RTSP 协议验证，把真正的摄像头标出来。摄像头每次上电 IP 会变，直接扫描即可，无需再去官方 APP 查 IP。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            LabeledTextField("账号", user, { user = it }, placeholder = "admin")
            LabeledTextField("密码", password, { password = it }, placeholder = "摄像头密码")
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                text = when {
                    scanning -> "扫描中…"
                    identifying -> "识别中…"
                    else -> "扫描并识别摄像头"
                },
                enabled = !busy && (subnet.isNotEmpty() || customSubnet.isNotEmpty()),
                onClick = {
                    val base = (if (customSubnet.isNotEmpty()) customSubnet else subnet).removeSuffix(".0")
                    val base24 = "$base.0"
                    val u = user.trim()
                    val p = password
                    scanning = true
                    identifying = false
                    devices = emptyList()
                    progress = 0 to 0
                    identifyProgress = 0 to 0
                    hint = null
                    scope.launch {
                        // 第 1 阶段：TCP 端口扫描
                        val hits = mutableListOf<Pair<String, Int>>()
                        scanLan(
                            base24 = base24,
                            ports = CAMERA_PORTS,
                            onProgress = { d, t -> progress = d to t },
                            onHit = { h, port ->
                                hits.add(h to port)
                                vm.addResult(
                                    TestResult(
                                        id = 0, scope = NetScope.LAN,
                                        target = "$h:$port", protocol = ProbeProtocol.TCP,
                                        success = true, latencyMs = 0,
                                        message = "发现开放端口（可能是摄像头/录像机）"
                                    )
                                )
                            }
                        )
                        scanning = false

                        // 第 2 阶段：按主机汇总后自动识别摄像头
                        val grouped = hits
                            .groupBy({ it.first }, { it.second })
                            .map { (h, ports) -> h to ports.distinct() }
                        if (grouped.isEmpty()) {
                            hint = "没有发现开放端口，请检查网段或摄像头是否在同一局域网"
                            return@launch
                        }
                        identifying = true
                        identifyProgress = 0 to grouped.size
                        val identified = identifyCameras(
                            hosts = grouped,
                            user = u,
                            password = p,
                            onProgress = { d, t -> identifyProgress = d to t }
                        )
                        val map = identified.associateBy { it.host }
                        devices = grouped.map { (h, ports) -> CameraDevice(h, ports, map[h]) }
                        identifying = false
                        if (identified.isEmpty()) {
                            hint = "扫描完成，但未能验证出摄像头。请确认账号密码是否正确，或点开设备手动测试"
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        if (scanning) {
            val (d, t) = progress
            LinearProgressIndicator(
                progress = { if (t > 0) d.toFloat() / t else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "正在扫描端口 $d / $t",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (identifying) {
            val (d, t) = identifyProgress
            LinearProgressIndicator(
                progress = { if (t > 0) d.toFloat() / t else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Green
            )
            Text(
                "正在验证摄像头 $d / $t（每台最多数秒，请稍候）",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "发现的设备（${devices.size}）— 点绿色「摄像头」直接播放，点其他设备进入手动测试",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = Muted)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    onClick = {
                        val identity = device.identity
                        if (identity != null) {
                            // 确认是摄像头 → 直接跳画面页自动播放
                            vm.setPendingRtsp(identity.rtspUrl)
                            vm.setRoute("play")
                        } else {
                            // 未知设备 → 跳手动测试页预填 IP
                            vm.updateLastTarget("${device.host}:${device.openPorts.firstOrNull() ?: 554}")
                            vm.setRoute("manual")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: CameraDevice, onClick: () -> Unit) {
    val identity = device.identity
    val isCam = identity != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(isCam)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (isCam) "摄像头 · ${identity!!.rtspUrl.removePrefix("rtsp://")}" else "仅开放端口 · 未知设备，点开手动测试",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCam) Green else Muted
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isCam) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Green.copy(alpha = 0.15f)
                ) {
                    Text(
                        "摄像头",
                        color = Green,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                ScopeBadge(NetScope.LAN)
            }
        }
    }
}
