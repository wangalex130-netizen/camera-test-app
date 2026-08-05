package com.example.cameratest.ui.screens

import android.net.Uri
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

@Composable
fun PlayerScreen() {
    val context = LocalContext.current

    // 预填你那台摄像头的 RTSP 地址（IP / 账号 / 密码已按你给的信息写好）
    var url by remember { mutableStateOf("rtsp://admin:abc123456@192.168.1.60:554/11") }
    var status by remember { mutableStateOf("待命") }
    var playing by remember { mutableStateOf(false) }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
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
                status = "播放失败：${error.localizedMessage ?: error.message ?: "未知错误"}"
                playing = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    fun startPlayback() {
        val u = url.trim()
        if (u.isEmpty()) return
        status = "连接中…"
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(u)))
        exoPlayer.setMediaSource(source)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    fun stopPlayback() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
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
                .aspectRatio(16f / 10f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!playing) {
                Text("点「播放」查看实时画面", color = Muted)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "状态：$status",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}
