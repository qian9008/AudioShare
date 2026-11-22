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
  * 使用专用的官方 `gradle/actions/setup-gradle@v4` 取代了 `setup-java` 内置的简易缓存，为 Android 编译流程下的 Gradle 依赖与构建提供更专业、可靠的缓存机制。
  * 针对 GitHub 缓存只读不可变导致的缓存碎片与 Tag 构建缓存浪费问题，通过配置 `cache-read-only: ${{ github.ref != 'refs/heads/master' }}`，将缓存更新权收归 `master` 分支。在非 `master` 分支（如 release tag 触发的构建）中只读复用现有缓存，不再回写，从根本上防止生成大量废弃的缓存，并保障构建性能。
  * 将 Gradle 编译命令调整为 `.\gradlew.bat assembleRelease --no-daemon`，强制在编译完成后销毁 Gradle 进程。这彻底解决了由于 Windows 文件系统上常驻守护进程（Daemon）锁死缓存目录引发的权限占用（Permission Denied），确保缓存能每次都在构建结束时成功保存。

### 4. HTTP 解密代理服务与网络请求重构
* **配置键**：`musiche-http-proxy`
* **存储结构**：持久化在 Android `SharedPreferences` 的 `"config"` 配置文件中。
* **通用工具函数与行为特征**：
  * **代理连接转发**：为了避免第三方网络库 HTTPS 隧道解密失败导致网络阻塞，安卓端原有的 `/proxy` 接口与各大音乐音源抓取模块均重构为基于原生 `HttpURLConnection` 进行手动代理转发。
  * **连接测试与证书校验**：新增了 `/proxy/test` 端点，不仅测试连通性，还依据特定证书响应特征判断解密代理服务（如 `UnblockNeteaseMusic`）是否工作正常。
  * **音源拉取工具 `executeRequest` 与协议保留**：在 [MusicItem.java](file:///d:/Users/Documents/1/airplay/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/MusicItem.java) 中新增了 `executeRequest` 静态辅助方法（支持忽略 SSL 校验、GZIP 解压与超时处理）。同时移除了原先将 `http://` 强制替换为 `https://` 的重写逻辑，以避免因第三方解密 CDN 节点（如波点、咪咕等）不支持 HTTPS 导致 SSL 证书握手失败而播放卡死。
  * **健壮的端口解析**：在解析代理 Host 与 Port 时，增加了 `replaceAll("[^\\d]", "")` 过滤，避免因代理字符串末尾包含斜杠或路径导致 `NumberFormatException` 崩溃。
  * **音频流的本地中转与安全策略绕过**：在 [AudioPlayer.java](file:///d:/Users/Documents/1/airplay/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/player/AudioPlayer.java) 中，如果配置了 HTTP 代理，或者播放链接是以外网明文 `http://` 开头，系统会自动将音频播放 URL 包装为本地 HttpServer 环回中转地址（`http://127.0.0.1:端口/proxy?url=...`）。这不仅能确保播放器音频下载流量全部经由代理，也能通过 localhost 转发，完美规避安卓系统在播放明文外网流媒体时的安全策略限制。

### 5. 网页端状态同步机制 (Web UI State Sync)
* **背景**：网页端（浏览器打开 `http://设备IP:端口`）是纯远程控制器。`musiche` 前端每次操作都 `checkingStatus = true` → 发 HTTP 请求 → 用**响应体**里的 `status` 覆盖本地状态（`play.ts` 的 `setStatus`），期间丢弃 WebSocket 推送。而 ExoPlayer 的 play/pause/seek 全部 `handler.post` 到主线程异步执行，导致"动作发出立刻取状态"拿到的是操作前的旧快照（`playing=false`、进度还是上一首），页面表现为点了播放没反应。
* **状态落地回调 `postWhenSettled`**（[AudioPlayer.java](file:///d:/Users/Documents/1/airplay/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/player/AudioPlayer.java)）：
  * `postWhenSettled(Runnable)` 把"回包任务"注册进一次性队列，等 `onDurationChanged` / `onPositionChanged` / `onPlayingChanged` 任一事件（均在主线程）触发时统一 `flushSettledTasks()` 执行；`900ms` 超时兜底，避免请求悬挂。
  * `postOnMain(Runnable)` 用于 seek 这类不产生播放状态变化、但需要实时位置的操作（排在 `seekTo` 的 post 之后执行）。
  * `play(String uri)` 结束时额外 `mMainHandler.post(flushSettledTasks)`：音源 URL 异步解析完成后立刻触发一次落地，让 `/play` 尽早回包。
* **实时位置读取**：`ExoPlayer.getPosition()/getDuration()` 在主线程直接读 `mediaPlayer` 实时值，避免缓存字段（每 500ms 才刷新）导致 seek 后进度条回跳。
* **HTTP 接口约定**：`/play`、`/pause`、`/progress` 走 `respondStatusWhenSettled()`（动作生效后再回包）；`/volume`、`/status` 走 `respondStatus()`（同步生效，无需等待）。所有回包与状态变更统一调用 `pushStatus()` 广播给全部网页 WebSocket 客户端。
* **推送补全**：`onPlaying(remote=false)`（服务端/通知栏发起的播放）、`onPaused()`、`onVolumeChanged()` 均补推网页端，此前只推多机互联 `sws` 通道。
* **`/ws` 字符串回调**：注册 `setStringCallback` 处理前端主动发送的 `/status`（`sendWsStatus()`），此前被静默丢弃。
* **监听器注册时机**：`HttpServer` 构造阶段即调用 `setOnLoadSuccessListener(this)`，不再依赖 `MainActivity` 绑定；`TcpService.startHttpServer()` 主动注入 `AudioManager`，保证开机自启/进程复活后网页端音量控制可用。

### 6. 上游同步工作流 (Upstream Sync)
* **仓库拓扑**：
  * 父仓库 `AudioShare`：`origin` = `qian9008/AudioShare`（fork），`upstream` = `HeHang0/AudioShare`（上游）。
  * 子模块 `musiche/`：`origin` = `qian9008/musiche`（fork），本地已追加 `upstream` = `HeHang0/musiche`。
* **初始化/同步命令**：
  ```bash
  git submodule update --init musiche          # 子模块是空目录时先初始化
  cd musiche
  git remote add upstream https://github.com/HeHang0/musiche
  git fetch upstream
  git checkout master
  git merge upstream/master                     # 用 merge，不要 rebase（本地 30+ 私有提交会被重写）
  ```
* **改动边界（2026-11 同步时确认）**：上游只动 `web/`、`room-server/`（Go 房间服务）、`.github/workflows/web.yml`；本 fork 的私有改动集中在 `mobile/lib`（安卓中转代理、状态页）、`proxy-server/`、`proxy-windows/`，上游零接触 → 该三块永不冲突。
* **固定冲突点（上游每次都会碰）**：`web/src/utils/http.ts`、`storage.ts`、`stores/connection.ts`、`views/setting.vue`、`components/Footer.vue`、`web/package.json`、`.github/workflows/web.yml`。
  * 代理地址：上游统一用 `'musiche-' + StorageKey.ProxyAddress` 读取并 `.replace(/"/g,'').trim()`；本地必须在同一套写法上叠加 `globalHttpProxy`（`StorageKey.HttpProxy`），不要再回到手工 `JSON.parse` 剥引号的旧写法。
  * CI：本 fork 主动关闭 `build-on-macos` / `build-on-ios` / `build-on-windows` 三个任务（仅保留 `build-web` + `build-on-android` + `publish`），并保留 `workflow_dispatch` 与 404 页面默认代理注入；上游版本含全平台任务，恢复时参考上游文件。
* **行尾约束（重要坑）**：`musiche/web/src` 下绝大部分文件是 **CRLF**（上游新增文件为 LF）。编辑器/工具保存时若把 CRLF 文件转成 LF，会造成「相对上游整文件差异」，下一次上游合并直接整文件冲突。改完 web 侧文件后务必检查：
  ```bash
  python3 -c "b=open('文件','rb').read(); print(b.count(b'\r\n'), b.count(b'\n'))"
  ```
  两数相等即为纯 CRLF。
* **上游新增能力（本次引入）**：`room-server`（Go，WebSocket/WebRTC 音乐房间、端到端加密聊天、凭证刷新）、Web 端 `room*.vue` 房间大厅/播放/中继、`capabilities` 播放器能力检测（`Footer.vue` 的音量控件受 `capabilities.volume` 控制）。
* **Android APK 打包 web 资源（卡首屏 logo 踩坑）**：`musiche/mobile/assets` 在 git 中是**符号链接** `../web/dist`，本地开发时自动指向构建产物。CI `build-on-android` 的 `Prepare assets` 步骤必须先 `rm -rf mobile/assets`（删链接）再 `mkdir -p mobile/assets` 然后 `cp -r web/dist/* mobile/assets/`。若漏掉 `mkdir`，`cp` 会因目标目录不存在失败；原上游用 `cp ... || true` 吞掉错误，Flutter 打出的 APK 内**没有任何 web 文件**（`index.html` 缺失），WebView 请求 `/index.html` 时 `_handleIndex` 找不到资源 → 302 重定向死循环 → 卡在首屏。**上游确实有此缺陷**（上游 9 月 APK 同样无 web 文件），但本 fork 原本用 `shell: pwsh` + `Copy-Item web\dist mobile\assets -Recurse` 规避了它（PowerShell 在目标不存在时会自动建目录）——合并前 `dde0de6` 的 APK 正常。**是 2026-11 合并上游时的 `b7e55af` 引入的回归**：该提交"以上游为底重写 web.yml"，把 pwsh + Copy-Item 换成了 bash + `cp -r ... || true`，丢掉了 fork 的规避写法，导致合并后 APK 卡首屏。修复：补 `mkdir -p mobile/assets` 并移除 cp 的 `|| true`（让后续失败在 CI 显式暴露）。**教训：合并 web.yml 时不能整段采用上游版本，必须逐 job 比对 fork 的本地适配（此处是 shell 与 Copy-Item 语义）。** 验货方法：`unzip -l APK | grep index.html` 应有结果。注意 APK 里 WebView 访问的是 Flutter 内置 HTTP server 的 `http://127.0.0.1:<port>/index.html`，是回环地址，无法从电脑通过设备 IP 访问调试。
* **模板静态属性禁止出现 `://`（踩坑记录）**：`vue-tsc` 3.3.x 在生成虚拟 TS 代码时，静态 HTML 属性中的 `//`（如 `placeholder="例如: http://127.0.0.1:1080"`）会被当成行注释起始，注释掉后续生成代码，导致**整份 SFC 所有 setup 绑定**的 `.value` 推断错乱，报出几十条 `TS2551/TS2339`。写法改为 `:placeholder="'...'"` 也无效。解决：静态属性里不要写 `http://`，例如写成 `placeholder="例如 127.0.0.1:1080"`。排查此类问题时，用 `@vue/compiler-sfc` 的 `compileScript` 看 `bindings` 是否被判成 `setup-maybe-ref`，再逐块回退模板 diff 定位触发属性。


### 6. 连接健壮性 (Connection Resilience)
* **网页 WebSocket 死连接回收**（[HttpServer.java](file:///d:/Users/Documents/1/play/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/HttpServer.java) `sendWSMessage`）：设备休眠、WiFi 掉线、浏览器被强杀时 TCP 不走正常关闭流程，`setClosedCallback` 可能不触发。现在发送异常即视为连接死亡，先收集再 `removeAll`（避免遍历中修改列表），单个僵尸连接不会再被每 500ms 推送反复命中。
* **UDP 发现线程自愈**（[BroadcastReceiver.java](file:///d:/Users/Documents/1/play/audioshare/android/app/src/main/java/com/picapico/audioshare/musiche/BroadcastReceiver.java)）：`receiveLoop` 外层循环重建 socket，端口被占 5s 重试、接收异常 2s 重试；只有 `stop()` 才退出，`start()` 用 `running` 标志防重复线程。原实现异常即线程死亡，UDP 抖动一次就永久失去多机发现能力。
* **本机 IP 快照刷新**：`localAddresses` 在每个接收周期重新采样（原为构造时一次性），避免 DHCP 换 IP 后把自己的广播当成其他设备、自己连自己造成回声。
