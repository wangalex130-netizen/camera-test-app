package com.example.cameratest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameratest.ui.components.ScopeBadge
import com.example.cameratest.ui.components.StatusDot
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel
import com.example.cameratest.data.NetScope
import com.example.cameratest.data.ProbeProtocol
import com.example.cameratest.data.TestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: AppViewModel = viewModel()) {
    val results by vm.results.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("测试历史（${results.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (results.isNotEmpty()) {
                TextButton(onClick = { vm.clear() }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无记录，去扫描或测试吧", color = Muted)
            }
        } else {
            LazyColumn {
                items(results) { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(r.success)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.target, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    ScopeBadge(r.scope)
                                }
                                Text(
                                    "${r.protocol.label} · ${fmt(r.timestamp)}${if (r.success && r.latencyMs > 0) " · ${r.latencyMs}ms" else ""}",
                                    style = MaterialTheme.typography.labelSmall, color = Muted
                                )
                                Text(r.message, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
