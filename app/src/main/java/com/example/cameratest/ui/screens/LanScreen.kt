package com.example.cameratest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameratest.data.NetScope
import com.example.cameratest.data.ProbeProtocol
import com.example.cameratest.data.TestResult
import com.example.cameratest.network.CAMERA_PORTS
import com.example.cameratest.network.NetworkUtils
import com.example.cameratest.network.scanLan
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.ScopeBadge
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.components.StatusDot
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
    var progress by remember { mutableStateOf(0 to 0) }
    var found by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var customSubnet by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (localIp != "未获取到") subnet = NetworkUtils.subnetBase24(localIp)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SectionCard(title = "本机网络") {
            Text("本地 IP：$localIp", style = MaterialTheme.typography.bodyMedium)
            Text("网段 /24：${subnet.ifEmpty { "—" }}${if (subnet.isEmpty()) "" else ".0/24"}", style = MaterialTheme.typography.bodyMedium, color = Muted)
            Spacer(Modifier.height(8.dp))
            LabeledTextField(
                label = "自定义网段（可选，留空用本机网段，如 192.168.1.0）",
                value = customSubnet,
                onValueChange = { customSubnet = it.trim() },
                placeholder = "192.168.1.0"
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (scanning) "扫描中…" else "开始内网扫描",
                enabled = !scanning && (subnet.isNotEmpty() || customSubnet.isNotEmpty()),
                onClick = {
                    val base = (if (customSubnet.isNotEmpty()) customSubnet else subnet).removeSuffix(".0")
                    val base24 = "$base.0"
                    scanning = true
                    found = emptyList()
                    progress = 0 to 0
                    scope.launch {
                        scanLan(
                            base24 = base24,
                            ports = CAMERA_PORTS,
                            onProgress = { d, t -> progress = d to t },
                            onHit = { h, p ->
                                found = (found + (h to p)).distinct()
                                vm.addResult(
                                    TestResult(
                                        id = 0, scope = NetScope.LAN,
                                        target = "$h:$p", protocol = ProbeProtocol.TCP,
                                        success = true, latencyMs = 0,
                                        message = "发现开放端口（可能是摄像头/录像机）"
                                    )
                                )
                            }
                        )
                        scanning = false
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
            Text("已探测 $d / $t", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text("发现的设备（${found.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(found) { (host, port) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("$host:$port", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("开放端口 · 建议尝试 RTSP/MJPEG", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        Spacer(Modifier.weight(1f))
                        ScopeBadge(NetScope.LAN)
                    }
                }
            }
        }
    }
}
