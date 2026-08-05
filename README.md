# 摄像头连接测试 (CameraTestApp)

安卓端摄像头 **内网 / 外网** 连接调试工具，用于快速排查摄像头（如 CamHipro / 海康 / 萤石 / ESP32-CAM 等）能否在局域网或公网被访问。

> 与桌面端预览器（深色 + 单一绿色 `#00D97E` 风格）保持一致的视觉语言。

## 功能

- **内网扫描 (LAN)**：自动获取本机 IP 与 /24 网段，并发探测常见摄像头端口（554 / 5544 / 80 / 8080 / 81 / 8000 / 34567 / 37777 等），自动发现在线设备。
- **外网测试 (WAN)**：输入域名或公网 IP + 端口，先 DNS 解析再做 TCP 探测，验证 **DDNS / 端口转发** 是否生效、能否从公网访问。
- **手动测试**：指定主机、端口、协议（TCP / RTSP / HTTP / MJPEG）、路径、账号密码，发送 RTSP `OPTIONS` 探测流是否可用。
- **历史记录**：保存所有探测结果，标注 内网/外网、协议、延迟、结果，可一键清空。

## 技术栈

- Kotlin + Jetpack Compose (Material3)
- minSdk 24 / targetSdk 34 / compileSdk 34
- AGP 8.5.2 · Kotlin 1.9.24 · Compose Compiler 1.5.14 · Gradle 8.9

## 构建与运行

1. 用 **Android Studio (Hedgehog 或更新)** 打开本项目根目录（Android Studio 会自动下载 Gradle 8.9 并同步依赖）。
2. 连接安卓设备或启动模拟器（模拟器无法访问你的真实局域网，建议用真机调试内网扫描）。
3. 点击 ▶ Run 安装到设备。
4. 首次使用授予网络权限（已在 Manifest 声明 `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`）。

> 命令行构建（需本地有 Gradle 8.9）：`gradle :app:assembleDebug`

## 权限说明

| 权限 | 用途 |
|---|---|
| INTERNET | TCP / RTSP 端口探测 |
| ACCESS_NETWORK_STATE | 读取网络状态 |
| ACCESS_WIFI_STATE | 读取本机 IP（备用） |

全部为普通权限，安装即授予，无需运行时弹窗。

## 目录结构

```
app/src/main/java/com/example/cameratest/
├── MainActivity.kt
├── data/Models.kt            # 数据模型（作用域 / 协议 / 结果）
├── network/                  # 探测核心
│   ├── NetworkUtils.kt       # 本机 IP / 网段
│   └── Probes.kt             # TCP / RTSP / WAN / 内网扫描
├── viewmodel/AppViewModel.kt # 历史记录状态
└── ui/
    ├── theme/Theme.kt
    ├── components/Components.kt
    ├── navigation/AppNav.kt  # 底部导航
    └── screens/              # 内网 / 外网 / 手动 / 历史
```

## 常见排查提示

- 内网扫不到设备：确认手机与摄像头连同一 Wi-Fi；部分路由器开启 AP 隔离会阻断局域网互访。
- RTSP 失败：确认路径（主码流 `/11`、子码流 `/12`）、账号密码；CamHipro 类常用 `rtsp://admin:密码@IP:554/11`。
- 外网不可达：检查路由器端口转发、DDNS 是否更新、运营商是否封禁 554 端口（可改用 5544 或高位端口）。
