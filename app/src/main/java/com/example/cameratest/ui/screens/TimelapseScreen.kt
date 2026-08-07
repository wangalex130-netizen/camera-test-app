package com.example.cameratest.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.theme.Muted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream

/** 从 VLCVideoLayout 内部递归找出渲染用的 TextureView（textureView 模式时 libVLC 内部使用它） */
private fun findTextureView(root: ViewGroup): TextureView? {
    for (i in 0 until root.childCount) {
        val child = root.getChildAt(i)
        when (child) {
            is TextureView -> return child
            is ViewGroup -> findTextureView(child)?.let { return it }
        }
    }
    return null
}

@Composable
fun TimelapseScreen() {
    val context = LocalContext.current
    var url by remember { mutableStateOf("rtsp://admin:abc123456@192.168.1.218:554/11") }
    var status by remember { mutableStateOf("待命") }
    var playing by remember { mutableStateOf(false) }

    var intervalStr by remember { mutableStateOf("2") }
    var totalStr by remember { mutableStateOf("30") }
    val interval = (intervalStr.toIntOrNull() ?: 2).coerceAtLeast(1)
    val total = (totalStr.toIntOrNull() ?: 30).coerceAtLeast(1)

    var capturing by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf(0) }
    var lastSaved by remember { mutableStateOf<String?>(null) }

    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val libVLC = remember(context) {
        runCatching {
            LibVLC(
                context,
                arrayListOf(
                    "--rtsp-tcp",
                    "--network-caching=100",
                    "--live-caching=100",
                    "--avcodec-hw=any",
                    "--drop-late-frames",
                    "--skip-frames",
                    "--no-audio"
                )
            )
        }.getOrNull()
    }
    val mediaPlayer = remember(context) {
        libVLC?.let { runCatching { MediaPlayer(it) }.getOrNull() }
    }

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
        if (u.isEmpty() || mediaPlayer == null || libVLC == null) return
        var finalUrl = u
        if (!finalUrl.startsWith("rtsp://", ignoreCase = true)) finalUrl = "rtsp://$finalUrl"
        if (!listOf("/11", "/12", "/13", "/live", "/stream", "/video", "/av", "/onvif").any { finalUrl.contains(it) }) {
            finalUrl = if (finalUrl.endsWith("/")) "${finalUrl}11" else "$finalUrl/11"
        }
        status = "连接中…"
        runCatching {
            // 关键修复：libVLC 不允许在 view 已附加时再次 attachViews，先彻底停止并 detach
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            val media = Media(libVLC, Uri.parse(finalUrl))
            mediaPlayer.media = media
            videoLayout?.let { layout ->
                mediaPlayer.attachViews(layout, null, false, true)
            }
            media.release()
            mediaPlayer.play()
            playing = true
        }.onFailure { e ->
            playing = false
            status = "播放失败：${e.message}"
        }
    }

    fun stopPlayback() {
        stopCapture()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
        }
        playing = false
        status = "已停止"
    }

    fun startCapture() {
        capturing = true
        captured = 0
        job = scope.launch {
            while (isActive && captured < total) {
                delay(interval * 1000L)
                val tv = videoLayout?.let { findTextureView(it) }
                val bmp = tv?.bitmap
                if (bmp != null) {
                    lastSaved = saveFrame(bmp, captured + 1)
                    captured++
                } else {
                    lastSaved = "未取到画面（请确认已播放）"
                }
            }
            capturing = false
        }
    }

    DisposableEffect(mediaPlayer) {
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener { event ->
                mainHandler.post {
                    when (event.type) {
                        MediaPlayer.Event.Opening -> status = "正在打开流…"
                        MediaPlayer.Event.Buffering -> status = "缓冲中…"
                        MediaPlayer.Event.Playing -> { playing = true; status = "已连接，播放中" }
                        MediaPlayer.Event.Stopped -> { playing = false; status = "已停止" }
                        MediaPlayer.Event.EncounteredError -> {
                            playing = false
                            stopCapture()
                            status = "播放失败：无法连接摄像头或流格式不受支持"
                        }
                        MediaPlayer.Event.EndReached -> { playing = false; status = "已结束" }
                    }
                }
            }
        }
        onDispose {
            runCatching { mediaPlayer?.setEventListener(null) }
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.detachViews()
                mediaPlayer?.release()
            }
            runCatching { libVLC?.release() }
        }
    }

    // 布局顺序改为：RTSP 配置 → 视频画面 → 延时参数（开始延时按钮在视频下方，无需滚动即可看到）；
    // 整个 Column 垂直可滚动，兼容小屏。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionCard(title = "实时画面（RTSP 拉流 · libVLC 内核）") {
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

        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            contentAlignment = Alignment.Center
        ) {
            if (mediaPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        VLCVideoLayout(ctx).also { layout ->
                            videoLayout = layout
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!playing) Text("点上方「播放」开始实时画面", color = Muted)
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "延时摄影") {
            // 输入框改为上下全宽排列，避免并排布局在小屏上被挤压导致无法填写
            LabeledTextField(
                "间隔（秒）",
                intervalStr,
                { intervalStr = it.filter { c -> c.isDigit() } },
                placeholder = "2"
            )
            LabeledTextField(
                "总张数",
                totalStr,
                { totalStr = it.filter { c -> c.isDigit() } },
                placeholder = "30"
            )
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
                    "停止延时",
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

        Spacer(Modifier.height(12.dp))
        Text("状态：$status", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(16.dp))
    }
}