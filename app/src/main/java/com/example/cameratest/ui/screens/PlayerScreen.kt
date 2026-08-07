package com.example.cameratest.ui.screens

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.example.cameratest.ui.components.LabeledTextField
import com.example.cameratest.ui.components.PrimaryButton
import com.example.cameratest.ui.components.SectionCard
import com.example.cameratest.ui.theme.Muted
import com.example.cameratest.viewmodel.AppViewModel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val TAG = "PlayerScreen"

/**
 * 实时画面（RTSP 拉流），使用 libVLC 播放内核。
 *
 * 为什么换掉 AndroidX Media3：
 *   - Media3 的 RTSP 模块在部分国产摄像头（CamHipro/V380 等）上会直接在内部
 *     网络线程抛异常导致进程崩溃，外层 try/catch 拦不住（线程级崩溃）。
 *   - libVLC 基于 ffmpeg，RTSP over TCP 兼容性业界最强，且所有错误都收敛到
 *     自己的 native 层并通过事件回调返回，不会把 APP 整个拖崩。
 *
 * 关键点：
 *  1. --rtsp-tcp 强制走 TCP（国产摄像头 UDP 经常不通）
 *  2. --network-caching=150 减小延迟
 *  3. --no-audio 跳过音频，只解视频，避免音频解码器冲突
 *  4. 播放器事件回调切回主线程再更新 Compose 状态
 *  5. 每次释放播放器都包 runCatching，绝不抛出
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
    var playerError by remember { mutableStateOf<String?>(null) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 创建 libVLC 播放内核（失败时返回 null，不会崩溃）
    val libVLC = remember(context) {
        runCatching {
            LibVLC(
                context,
                arrayListOf(
                    "--rtsp-tcp",
                    "--network-caching=150",
                    "--no-audio"
                )
            )
        }.onFailure { Log.e(TAG, "LibVLC 初始化失败", it) }.getOrNull()
    }

    val mediaPlayer = remember(context) {
        libVLC?.let { vlc ->
            runCatching { MediaPlayer(vlc) }
                .onFailure { Log.e(TAG, "MediaPlayer 创建失败", it) }
                .getOrNull()
        }
    }

    DisposableEffect(mediaPlayer) {
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener { event ->
                mainHandler.post {
                    when (event.type) {
                        MediaPlayer.Event.Opening -> status = "正在打开流…"
                        MediaPlayer.Event.Buffering -> status = "缓冲中…"
                        MediaPlayer.Event.Playing -> {
                            playing = true
                            playerError = null
                            status = "已连接，播放中"
                        }
                        MediaPlayer.Event.Stopped -> {
                            playing = false
                            status = "已停止"
                        }
                        MediaPlayer.Event.EndReached -> {
                            playing = false
                            status = "已结束"
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            playing = false
                            val msg = "无法连接摄像头，或该流格式不受支持"
                            playerError = msg
                            status = "播放失败"
                        }
                    }
                }
            }
        }
        onDispose {
            runCatching {
                mediaPlayer?.setEventListener(null)
            }.onFailure { Log.e(TAG, "移除监听失败", it) }
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.detachViews()
                mediaPlayer?.release()
            }.onFailure { Log.e(TAG, "释放播放器失败", it) }
            runCatching { libVLC?.release() }
                .onFailure { Log.e(TAG, "释放 LibVLC 失败", it) }
        }
    }

    fun startPlayback() {
        val u = url.trim()
        if (u.isEmpty()) {
            playerError = "请先填写 RTSP 地址"
            status = playerError!!
            return
        }
        val mp = mediaPlayer
        val vlc = libVLC
        if (mp == null || vlc == null) {
            playerError = "播放器初始化失败，此设备无法使用视频播放"
            status = playerError!!
            return
        }

        var finalUrl = u
        if (!finalUrl.startsWith("rtsp://", ignoreCase = true)) {
            finalUrl = "rtsp://$finalUrl"
        }
        // 没有具体路径时补 /11（默认主码流）
        val hasPath = listOf("/11", "/12", "/13", "/live", "/stream", "/video", "/av", "/onvif")
            .any { finalUrl.contains(it) }
        if (!hasPath) {
            finalUrl = if (finalUrl.endsWith("/")) "${finalUrl}11" else "$finalUrl/11"
        }

        status = "连接中…"
        playerError = null
        runCatching {
            // 关键修复：libVLC 不允许在 view 已附加时再次 attachViews。
            // 重播/换源前必须先彻底停止并 detachViews，否则抛
            // "Can't set view when already attached. Current state: 2"。
            mp.stop()
            mp.detachViews()

            val media = Media(vlc, Uri.parse(finalUrl))
            mp.media = media
            videoLayout?.let { layout ->
                // 最后一个参数 textureView=true：内部用 TextureView 渲染
                mp.attachViews(layout, null, false, true)
            }
            media.release()
            mp.play()
            playing = true
        }.onFailure { e ->
            Log.e(TAG, "启动播放失败: $finalUrl", e)
            playing = false
            playerError = "启动播放失败: ${e.message}"
            status = playerError!!
        }
    }

    fun stopPlayback() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
        }.onFailure { Log.e(TAG, "停止失败", it) }
        playing = false
        status = "已停止"
    }

    // 由内网/手动页跳转而来：自动填充并播放识别到的摄像头
    val pendingRtsp by vm.pendingRtsp.collectAsState()
    LaunchedEffect(pendingRtsp) {
        val target = pendingRtsp
        if (target != null) {
            url = target
            vm.setPendingRtsp(null) // 消费掉，避免重组时重复触发
            startPlayback()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard(title = "实时画面（RTSP 拉流 · libVLC 内核）") {
            LabeledTextField(
                "RTSP 地址",
                url,
                { url = it },
                placeholder = "rtsp://admin:abc123456@192.168.1.218:554/11"
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "传输方式已固定为 RTP over TCP（国产摄像头最稳）",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
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
                .aspectRatio(16f / 10f)
                .background(Color.Black),
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
            if (!playing) {
                Text(
                    if (playerError != null) "播放失败，详见下方提示" else "点「播放」查看实时画面",
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