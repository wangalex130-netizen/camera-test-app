package com.example.cameratest.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.theme.Muted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun TimelapseScreen() {
    val context = LocalContext.current
    var url by remember { mutableStateOf("rtsp://admin:abc123456@192.168.1.60:554/11") }
    var status by remember { mutableStateOf("待命") }
    var playing by remember { mutableStateOf(false) }

    var intervalStr by remember { mutableStateOf("2") }
    var totalStr by remember { mutableStateOf("30") }
    val interval = (intervalStr.toIntOrNull() ?: 2).coerceAtLeast(1)
    val total = (totalStr.toIntOrNull() ?: 30).coerceAtLeast(1)

    var capturing by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf(0) }
    var lastSaved by remember { mutableStateOf<String?>(null) }

    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun stopCapture() {
        job?.cancel()
        job = null
        capturing = false
    }

    fun saveFrame(bmp: Bitmap, index: Int): String? {
        val name = "frame_%05d.jpg".format(index)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraTestApp")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                context.contentResolver.openOutputStream(uri!!)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                "相册 / Pictures/CameraTestApp/$name"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CameraTestApp")
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                f.absolutePath
            }
        } catch (e: Exception) { "保存失败：${e.localizedMessage}" }
    }

    fun startPlayback() {
        val u = url.trim()
        val tv = textureViewRef.value
        if (u.isEmpty() || tv == null) return
        status = "连接中…"
        try {
            exoPlayer.setVideoTextureView(tv)
            val source = RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(u)))
            exoPlayer.setMediaSource(source)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        } catch (e: Exception) {
            status = "播放失败：${e.localizedMessage}"
        }
    }

    fun stopPlayback() {
        stopCapture()
        exoPlayer.stop()
        exoPlayer.setVideoTextureView(null)
        playing = false
        status = "已停止"
    }

    fun startCapture() {
        capturing = true
        captured = 0
        job = scope.launch {
            while (isActive && captured < total) {
                delay(interval * 1000L)
                val bmp = textureViewRef.value?.bitmap
                if (bmp != null) {
                    lastSaved = saveFrame(bmp, captured + 1)
                    captured++
                } else {
                    lastSaved = "未取到画面（请先播放）"
                }
            }
            capturing = false
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_IDLE -> "待命"
                    Player.STATE_BUFFERING -> "连接 / 缓冲中…"
                    Player.STATE_READY -> { playing = true; "已连接，播放中" }
                    Player.STATE_ENDED -> "结束"
                    else -> status
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                status = "播放失败：${error.localizedMessage ?: error.message ?: "未知错误"}"
                playing = false
                stopCapture()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard(title = "实时画面（RTSP 拉流）") {
            LabeledTextField("RTSP 地址", url, { url = it }, placeholder = "rtsp://user:pass@host:554/11")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryButton(
                    "播放",
                    enabled = !playing && url.isNotBlank(),
                    onClick = { startPlayback() },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    "停止",
                    enabled = playing,
                    onClick = { stopPlayback() },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "延时摄影") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledTextField(
                    "间隔（秒）",
                    intervalStr,
                    { intervalStr = it.filter { c -> c.isDigit() } },
                    placeholder = "2",
                    modifier = Modifier.weight(1f)
                )
                LabeledTextField(
                    "总张数",
                    totalStr,
                    { totalStr = it.filter { c -> c.isDigit() } },
                    placeholder = "30",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryButton(
                    "开始延时",
                    enabled = playing && !capturing,
                    onClick = { startCapture() },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    "停止",
                    enabled = capturing,
                    onClick = { stopCapture() },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("进度：$captured / $total", style = MaterialTheme.typography.bodyMedium, color = Muted)
            lastSaved?.let {
                Text(
                    "最近：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        keepScreenOn = true
                        textureViewRef.value = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!playing) Text("点「播放」开始实时画面，再点「开始延时」", color = Muted)
        }

        Spacer(Modifier.height(8.dp))
        Text("状态：$status", style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
