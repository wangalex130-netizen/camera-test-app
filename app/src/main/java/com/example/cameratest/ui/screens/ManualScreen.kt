package com.example.cameratest.ui.screens

import androidx.compose.foundation.layout.*
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
import com.example.cameratest.network.probeByProtocol
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.components.StatusDot
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(vm: AppViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("192.168.1.60") }
    var port by remember { mutableStateOf("554") }
    var path by remember { mutableStateOf("/11") }
    var user by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("abc123456") }
    var protocol by remember { mutableStateOf(ProbeProtocol.RTSP) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf(0L) }

    val protocols = ProbeProtocol.values().toList()
    var exp by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard(title = "手动连接测试") {
            LabeledTextField("主机（IP 或域名）", host, { host = it }, placeholder = "192.168.1.60")
            LabeledTextField("端口", port, { port = it.filter { c -> c.isDigit() } }, placeholder = "554")

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { exp = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(protocol.label, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = exp)
                }
                DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                    protocols.forEach {
                        DropdownMenuItem(text = { Text(it.label) }, onClick = { protocol = it; exp = false })
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            LabeledTextField("路径（RTSP/MJPEG 用，如 /11、/stream）", path, { path = it }, placeholder = "/11")
            LabeledTextField("账号（可选）", user, { user = it })
            LabeledTextField("密码（可选）", password, { password = it }, placeholder = "摄像头密码")

            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (testing) "测试中…" else "开始测试",
                enabled = !testing && host.isNotBlank() && port.isNotBlank(),
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val p = port.toIntOrNull() ?: 554
                        val r = probeByProtocol(host.trim(), p, protocol, path.trim(), user.trim(), password)
                        testing = false
                        ok = r.reachable
                        latency = r.latencyMs
                        result = r.detail
                        vm.addResult(
                            TestResult(
                                id = 0, scope = if (host.contains("192.168") || host.contains("10.") || host.contains("172.")) NetScope.LAN else NetScope.WAN,
                                target = "$host:$p", protocol = protocol,
                                success = r.reachable, latencyMs = r.latencyMs,
                                message = r.detail
                            )
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        result?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (ok) "连接成功" else "连接失败", fontWeight = FontWeight.SemiBold)
                        if (ok) Text("延迟：$latency ms", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
