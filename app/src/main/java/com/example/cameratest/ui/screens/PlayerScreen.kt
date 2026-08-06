package com.example.cameratest.ui.screens

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel

private const val TAG = "PlayerScreen"

/**
 * 实时画面（RTSP 拉流）。
 * 关键防闪退：
 *  1. ExoPlayer 创建包 try/catch，建失败时直接给错误提示而不是 crash
 *  2. setMediaSource/prepare 包 try/catch
 *  3. onPlayerError 接收 ExoPlayer 回调，写到 status 上而不是抛回主线程
 *  4. DisposableEffect 在 onDispose 时安全释放（catch Throwable）
 *  5. 路径为空时自动补 /11
 *  6. TCP / UDP 可切换（国产摄像头 RTSP 用 UDP 经常不通，TCP 更稳）
 */
@Composable
fun PlayerScreen(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val defaultIp = vm.lastTarget.substringBefore(":").ifBlank { "192.168.1.218" }
    val defaultPort = vm.lastTarget.substringAfter(":", "").ifBlank { "554" }
    val defaultUrl = "rtsp://admin:abc123456@$defaultIp:$defaultPort/11"

    var url by remember(vm.lastTarget) { mutableStateOf(defaultUrl) }
    var status by remember { mutableStateOf("待命") }
    var playing by remember { mutableStateOf(false) }
    var useTcp by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }

    // 关键：用一个稳定的 ExoPlayer 实例（在 remember 里创建失败的话也保留 null 状态）
    val exoPlayer: ExoPlayer? = remember(context) {
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
            }
        }.onFailure { e ->
            Log.e(TAG, "ExoPlayer.Builder 失败", e)
            null
        }.getOrNull()
    }

    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) {
            status = "播放器初始化失败"
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    status = when (state) {
                        Player.STATE_IDLE -> "待命"
                        Player.STATE_BUFFERING -> "连接 / 缓冲中…"
                        Player.STATE_READY -> {
                            playing = true
                            "已连接，播放中"
                        }
                        Player.STATE_ENDED -> "结束"
                        else -> status
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer 播放错误", error)
                    playing = false
                    val code = error.errorCodeName
                    val msg = error.localizedMessage ?: error.message ?: "未知错误"
                    playerError = "播放失败 ($code): $msg"
                    status = "播放失败：$msg"
                }
            }
            exoPlayer.addListener(listener)
            onDispose {
                runCatching {
                    exoPlayer.removeListener(listener)
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.release()
                }.onFailure { Log.e(TAG, "释放播放器失败", it) }
            }
        }
    }

    fun startPlayback() {
        val u = url.trim()
        if (u.isEmpty() || exoPlayer == null) return
        status = "连接中…"
        playerError = null

        // 自动补路径：用户可能只填了 rtsp://user:pass@host:port
        val finalUrl = if (u.contains("/11") || u.contains("/live") || u.contains("/stream") || u.contains("/video") || u.contains("/av") || u.contains("/onvif")) {
            u
        } else {
            // 没有具体路径时，默认 /11
            if (u.endsWith("/")) "${u}11" else "$u/11"
        }

        runCatching {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .createMediaSource(MediaItem.fromUri(Uri.parse(finalUrl)))
            exoPlayer.setMediaSource(source)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        }.onFailure { e ->
            Log.e(TAG, "startPlayback 失败", e)
            playing = false
            playerError = "启动播放失败: ${e.message}"
            status = playerError!!
        }
    }

    fun stopPlayback() {
        if (exoPlayer == null) return
        runCatching {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }.onFailure { Log.e(TAG, "stop 失败", it) }
        playing = false
        status = "已停止"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard(title = "实时画面（RTSP 拉流）") {
            LabeledTextField(
                "RTSP 地址",
                url,
                { url = it },
                placeholder = "rtsp://user:pass@host:554/11"
            )
            Spacer(Modifier.height(8.dp))
            // TCP / UDP 切换
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = useTcp,
                    onCheckedChange = { useTcp = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (useTcp) "传输：RTP over TCP（推荐，国产摄像头用 TCP 更稳）"
                    else "传输：RTP over UDP（部分摄像头需 UDP）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryButton(
                    text = if (playing) "播放中" else "播放",
                    enabled = !playing && url.isNotBlank(),
                    onClick = { startPlayback() },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = "停止",
                    enabled = playing,
                    onClick = { stopPlayback() },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 视频画面区（16:10，黑底；未播放时显示提示）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (exoPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        runCatching {
                            PlayerView(ctx).apply {
                                useController = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                                player = exoPlayer
                            }
                        }.getOrElse { e ->
                            Log.e(TAG, "PlayerView 创建失败", e)
                            android.widget.TextView(ctx).apply {
                                text = "PlayerView 创建失败"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!playing) {
                Text(
                    if (playerError != null) "" else "点「播放」查看实时画面",
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "状态：$status",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        playerError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "常见 RTSP 路径（可手填 URL 后缀）：/11  /12  /live/ch00_0  /streaming/channels/101  /onvif/streaming/channels/101",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}