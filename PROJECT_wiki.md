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
* **构建产物分发 (避免打包 zip)**：移除了 `actions/upload-artifact` 步骤，改为在普通 `push` 提交时自动创建或覆盖更新名为 `dev-build` 的 **Prerelease (开发预发布)**。这可以直接在 Releases 页面以原始单文件形式分发 `AudioShare.apk`、`AudioShare.exe` 等产物，彻底避免了 GitHub Actions 本身将产物打包成 `.zip` 压缩包所带来的解压不便。
* **构建缓存与依赖对齐**：
  * 将 `release.yml` 的通用 Actions 库版本（如 `checkout@v6`、`setup-node@v6`、`setup-java@v5`、`action-gh-release@v3`）全面与项目自带的 [web.yml](file:///d:/Users/Documents/1/airplay/audioshare/musiche/.github/workflows/web.yml) 对齐。
  * 将 Web 端前端缓存的依赖路径由 `musiche/web/package.json` 替换为 `musiche/web/pnpm-lock.yaml`。由于 package.json 内版本有模糊匹配前缀，用锁定绝对版本的 pnpm-lock.yaml 能根治前端依赖由于版本微小变化频繁 Cache Miss 进而每次都保存新依赖包的问题。
  * 使用专用的官方 `gradle/actions/setup-gradle@v4` 取代了 `setup-java` 内置的简易缓存，为 Windows 平台下的 Gradle 依赖与构建提供更专业、可靠的缓存机制。
  * 将 Gradle 编译命令调整为 `.\gradlew.bat assembleRelease --no-daemon`，强制在编译完成后销毁 Gradle 进程。这彻底解决了由于 Windows 文件系统上常驻守护进程（Daemon）锁死缓存目录引发的权限占用（Permission Denied），确保缓存能每次都在构建结束时成功保存。

### 4. HTTP 解密代理服务与网络请求重构
* **配置键**：`musiche-http-proxy`
* **存储结构**：持久化在 Android `SharedPreferences` 的 `"config"` 配置文件中。
* **通用工具函数与行为特征**：
  * **代理连接转发**：为了避免第三方网络库 HTTPS 隧道解密失败导致网络阻塞，安卓端原有的 `/proxy` 接口与各大音乐音源抓取模块均重构为基于原生 `HttpURLConnection` 进行手动代理转发。
  * **连接测试与证书校验**：新增了 `/proxy/test` 端点，不仅测试连通性，还依据特定证书响应特征判断解密代理服务（如 `UnblockNeteaseMusic`）是否工作正常。
  * **音源拉取工具 `executeRequest`**：在 [MusicItem.java](file:///d:/Users/Documents/1/airplay/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/MusicItem.java) 中新增了 `executeRequest` 静态辅助方法。该方法具备代理感知能力，同时支持忽略不安全 SSL 证书校验、GZIP 解压缩和连接超时控制。
  * **健壮的端口解析**：在解析代理 Host 与 Port 时，增加了 `replaceAll("[^\\d]", "")` 过滤，避免因代理字符串末尾包含斜杠或路径导致 `NumberFormatException` 崩溃。
