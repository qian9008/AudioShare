# AudioShare & Musiche 开发维基 (PROJECT_wiki)

## 核心机制与通用配置

### 1. 多机互联配置 (Multi-device Synchronization)
* **配置键**：`multiDeviceSync`
* **存储结构**：存储在 Android `SharedPreferences` 中的 `"musiche-setting"` 键对应的 JSON 字符串中。在 Web 端，该项对应 Pinia store [setting.ts](file:///d:/Users/Documents/1/airplay/audioshare/musiche/web/src/stores/setting.ts) 的 `pageValue.multiDeviceSync`。
* **默认状态**：`false` (默认关闭多机互联广播自发现和 WebSocket 连接)。
* **逻辑控制与行为特征**：
  * 当该开关为 `false` 时，安卓端 [HttpServer.java](file:///d:/Users/Documents/1/airplay/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/HttpServer.java) 将不会加载 `BroadcastReceiver`，以避免在局域网内发出 UDP 广播包以及监听其他设备的发现信号。同时，会调用所有已连接客户端的 `disconnect()` 方法主动释放 Socket，以彻底断开不必要的局域网连接。
  * 当用户在 Web 端勾选并开启此功能时，设置通过 `/storage` 接口持久化到安卓设备中，安卓端立刻捕获变更并动态开启 `BroadcastReceiver` 进行局域网内设备的相互探测和对等连接（热生效，无需重启 App）。

### 2. 音频通道优先级与互斥
* **工作原理**：
  当 Android 客户端（`TcpService`）与 Windows 端建立连接并准备开始解码播放 Windows 系统音频时，会在 `playAudio` 阶段强制调用 `httpServer.getAudioPlayer().pause()`，挂起正在播放的本地/云端音乐，腾出底层硬件声道资源供电脑实时音频流独占。

### 3. CI/CD 构建与产物分发 (GitHub Actions)
* **配置文件**：[.github/workflows/release.yml](file:///d:/Users/Documents/1/airplay/audioshare/.github/workflows/release.yml)
* **构建产物上传**：加入了 `actions/upload-artifact@v4` 步骤。如今每次对 master 的推送（Push）行为，即使不推送 Tag 触发 Release 发布，也能自动在 GitHub Action 的每一次运行历史（Runs）的 Summary 页面下方提供已打包的构建产物（`AudioShare-Builds.zip`），包含编译好的 APK 和 EXE，极大地方便了日常的开发与测试。
