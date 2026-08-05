package com.example.cameratest.ui.screens

import androidx.compose.foundation.layout.*
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
import com.example.cameratest.network.wanProbe
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.ScopeBadge
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.components.StatusDot
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun WanScreen(vm: AppViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("554") }
    var testing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var resolvedIp by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf(0L) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard(title = "外网连通性测试（DDNS / 端口转发）") {
            LabeledTextField("域名或公网 IP", host, { host = it }, placeholder = "cam.example.ddns.net 或 1.2.3.4")
            LabeledTextField("端口", port, { port = it.filter { c -> c.isDigit() } }, placeholder = "554")
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (testing) "测试中…" else "开始外网测试",
                enabled = !testing && host.isNotBlank() && port.isNotBlank(),
                onClick = {
                    testing = true
                    resultText = null
                    resolvedIp = null
                    scope.launch {
                        val p = port.toIntOrNull() ?: 554
                        val r = wanProbe(host.trim(), p)
                        testing = false
                        ok = r.reachable
                        latency = r.latencyMs
                        resolvedIp = r.resolvedIp
                        resultText = r.detail
                        vm.addResult(
                            TestResult(
                                id = 0, scope = NetScope.WAN,
                                target = "$host:$p", protocol = ProbeProtocol.TCP,
                                success = r.reachable, latencyMs = r.latencyMs,
                                message = r.detail + (if (r.resolvedIp != null) " · 解析=$resolvedIp" else "")
                            )
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        resultText?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (ok) "外网可达" else "外网不可达", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            ScopeBadge(NetScope.WAN)
                        }
                        if (resolvedIp != null) Text("解析到：$resolvedIp", style = MaterialTheme.typography.labelMedium, color = Muted)
                        if (ok) Text("延迟：$latency ms", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "说明：此项验证摄像头是否可以从公网访问。需路由器已做端口转发或摄像头启用了 P2P/云服务。\n" +
                    "测试成功 = 域名解析正常且对应端口对外可达；失败多因未做转发、公网 IP 变化或运营商封禁 554。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
    }
}
