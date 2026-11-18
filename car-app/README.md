# car-app — MyGo Music 车机版 Android 工程（阶段 D … J）

工作区内的 Kotlin/Compose 车机工程，消费 `android-bridge` 产出的 `mygobridge.aar`，
用 Media3/ExoPlayer 替换桌面端 `mygo/audio`（方案 B：Go 只解析 `music.Stream{URL, Headers}`，
Media3 负责下载/解封装/解码）。

## 构建

```bash
cd car-app
bash build.sh assemble              # 车机版 debug（arm64 单架构）
bash build.sh assemble -Pemulator   # 模拟器版 debug（arm64+x86_64）
bash build.sh assembleRelease       # 车机版 release（需先 make-keystore.sh，见「发布」）
bash build.sh check                 # Kotlin 编译 + lint
bash build.sh connectedDebugAndroidTest -Pemulator   # 仪器化测试（需已连设备）
bash build.sh clean
```

工具链全部固化在工作区 `.toolchain/`，`build.sh` 自己设置 `JAVA_HOME`/`ANDROID_HOME`/
`GRADLE_USER_HOME`，不依赖调用者 PATH：

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `.toolchain/jdk17` | Microsoft OpenJDK 17.0.21.0.2 |
| Gradle | `.toolchain/gradle-8.7` | 8.7 |
| Android SDK | `.toolchain/android-sdk` | platform 35 / build-tools 35.0.0+34.0.0 / cmdline-tools |
| NDK | `.toolchain/android-sdk/ndk/27.3.13750724` | r27c（仅 AAR 构建用） |

首次构建需联网下载 AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.03 / Media3 1.4.1；
`settings.gradle.kts` 使用 `google()` + `mavenCentral()`，两者在本机可达。

## 关键约束

- `minSdk = 24`：与 AAR 的 `-androidapi 24` 对齐；AAR 只含 `arm64-v8a` JNI，故 `abiFilters` 限定 arm64-v8a。
- `targetSdk = 35`，`compileSdk = 35`；AGP 8.5.2 对 35 有警告，已在 `gradle.properties` 用 `android.suppressUnsupportedCompileSdk=35` 抑制（Gradle 8.7 与 AGP 8.5.2 是兼容上界）。
- AAR 通过 `implementation(files("../../build/mygobridge.aar"))` 引入，**不修改 AGPL 原仓库**。
- 三个插件源码放在 `app/src/main/assets/plugins/`，运行时由 `GoBridge` 递归复制到 `filesDir/plugins`，因为 Go 侧 `plugin.Discover` 扫描的是 `<dataDir>/plugins`。

## MUSE 视觉系统（阶段 K）

主题取自《音乐播放器 UI 重设计.pdf》：炭黑 `#0F0E0E` / 暖金 `#E8C268` / 纸白 `#F2F2F2` / 深棕 `#2E2620`。
`CarColors.Cyan` 是暖金的兼容别名。封面图案由 `Canvas` 离线绘制（声纹同心圆 / 波浪 / 斜切圆 / 山脊），
不拉网络图片。播放页为「大封面 + 中央信息与进度 + 右侧队列」三栏；歌词页用深棕渐变底与大字高亮；
播放页右上「专注」进入全屏驾驶模式，右上「退出专注」返回。
车机窗口只有 411dp 高，故卡片改为 5 列、比例 1.9（设计稿为 4 列 / 1.35），确保标题不被播放条裁掉。

## 登录与详情页（阶段 J）

- 插件页的「登录」按钮由 `listPluginsJSON` 的 `signIn` 能力位驱动，没有登录导出的音源不显示按钮。
  喜马拉雅走 `webSignIn` → `SignInActivity` 的 passport 页；网易云/QQ 音乐走 `signInPrompt`
  二维码（`SignInPromptSheet` 用 Canvas 画 Go 侧 `rsc.io/qr` 编出的矩阵）。扫码期间可收起卡片，
  登录仍在进行；完成后通过 `account` 事件刷新按钮为「已登录 / 退出」。
- 详情页按 `show / album / artist / playlist` 依次尝试插件导出，再退回 `p.Browse(id)`。
  UI 写「专辑」而 ximalaya-web 只导出 `show`，靠这条回退打开；云听的 `playback:<id>` 节目列表走兜底。
- 搜索同时查 `tracks` 与 `shows`（`shows` 为空才试 `albums`）；「专辑与节目」区排在歌曲之前。
  卡片尺寸固定为 `TileHeight=116dp` / `TileCover=88dp`——车机 411dp 高的窗口会把大卡片压到只剩封面。

## 代码结构

| 文件 | 职责 |
|---|---|
| `MyGoCarApp.kt` | Application 主线程创建 ExoPlayer |
| `player/PlaybackHolder.kt` | 单例 ExoPlayer + `ResolvingDataSource` 注入 Go 给的 HTTP headers |
| `player/MediaOutputImpl.kt` | 实现 `facade.MediaOutput`：`load/play/pause/seekMs/setVolume`；`STATE_ENDED` 回调 `Facade.ended()` 让 Go 推进队列 |
| `player/PlaybackService.kt` | `MediaSessionService`，向方向盘按键/蓝牙耳机/系统通知暴露播放控制 |
| `bridge/BridgeHost.kt` | 实现 `facade.HostBridge`：`openURL`、`toast`、`webSignInJSON`（阶段 E 走 WebView 登录） |
| `bridge/BridgeListener.kt` | 实现 `facade.Listener`，把 Go 事件转成 `SharedFlow` |
| `bridge/GoBridge.kt` | 安装 assets 插件、注入三个接口、调用 `Facade.init(filesDir)`、启动前台服务 |
| `bridge/PluginInstaller.kt` | SAF zip → 私有目录复制、repo/卸载/来源/更新检查，全部在 `Dispatchers.IO` |
| `bridge/SignInActivity.kt` | 私有 WebView 登录页，等 `until` cookie 齐或用户取消 |
| `bridge/SignInCoordinator.kt` | 同步 Go 调用 ↔ 异步 Activity 的 CountDownLatch 桥（含超时、Cookie 隔离） |
| `bridge/InstallRequest.kt` | App Link 安装请求的跨组件持有者 |
| `ui/SmokeScreen.kt` | 阶段 D/E 冒烟面板，每个按钮隔离链路中的一环 |

## 阶段 E：插件接线

- **SAF 安装**：`⑥ 从文件安装插件` 选 zip → `content://` Uri 复制到 `filesDir` → `Facade.installPluginFromPath`。
- **App Link**：`mygocar://install?repo=owner/repo`（Manifest intent-filter）→ MainActivity `handleInstallIntent` → SmokeScreen 消费 pending repo 并调用 `installFromRepo`。
- **WebView 登录**：`webSignInJSON` 请求带 `url`/`cookiesOf`/`until`/`userAgent`；`SignInCoordinator` 在 Go 线程阻塞等待，主线程起 `SignInActivity`，CookieManager 检测到所有 `until` cookie 后返回 Cookie 头；取消/超时返回空串。
- **媒体键转发**：`PlaybackService` 的 `MediaSession.Callback.onPlayerCommandRequest` 把 PLAY_PAUSE / SEEK_TO_NEXT / SEEK_TO_PREVIOUS 转发给 Go（`RESULT_SUCCESS` 阻止 Media3 二次应用），Go 侧 `playing`/队列保持唯一真相源。
- **Go facade 新增**：`installPluginFromPath` / `installPluginFromRepo` / `uninstallPlugin` / `pluginOriginsJSON` / `checkPluginUpdatesJSON`；安装后 `reloadPluginLocked` 关闭旧实例并替换内存插件列表；本地安装无 origin.json 时用 `music.Info.Version` 回填版本显示。

### Media3 headers 的实现说明

Media3 1.4.1 的 `MediaItem.Builder` **没有** `setHeaders()`（这是首轮编译实测报错）。
实现改为在 `PlaybackHolder` 中用 `ResolvingDataSource.Factory(DefaultHttpDataSource.Factory())`
包装，`MediaOutputImpl.load()` 先 `PlaybackHolder.setHeaders(...)` 再 `setMediaItem()`+
`prepare()`，从而支持每一首歌独立的请求头（token/UA 等）。HLS 由 `media3-exoplayer-hls`
原生解码，不再依赖桌面端 `host.spawn` 的 Python/ffmpeg 桥接。

## 冒烟步骤（需 arm64 真机或车机）

1. 安装 `app-debug.apk`。
2. 点 **① 初始化 Go 桥接**：复制插件、注入 HostBridge/MediaOutput/Listener、`Facade.init` 应返回 3。
3. 点 **② 列出插件**：应见 demo / cnradio / ximalaya-web，并自动选中首个。
4. 点 **③ Browse**：查看返回 JSON；若选中插件首页无 tracks，任选其他插件。
5. 点 **④ 播放 Browse 第一首**：走完整 Go → Media3 链路，观察是否出声、`Last event` 是否出现 `track` 事件。
6. 点 **⑤ 直连本地 tone.mp3**：绕过 Go 直接验证 Media3 与音频焦点，用于定位问题是桥接还是播放器。
7. 点 **⏯ 暂停/继续**：验证经 Go 的控制路径（蓝牙/方向盘键走同一路径）。
8. 阶段 E：**⑥** 选一个插件 zip 安装；**⑦** 输入 `owner/repo` 从 GitHub 安装；**⑧** 看来源；**⑨** 查更新（联网）；**⑩** 按 ID 卸载。
9. App Link 验证：`adb shell am start -a android.intent.action.VIEW -d 'mygocar://install?repo=owner/repo'`，应用会弹出并开始安装。
10. WebView 登录：让需要登录的插件（如 ximalaya-web）触发 `host.webSignIn`，应弹出独立 WebView，登录成功后 Cookie 回传插件。

若 ④ 不出声而 ⑤ 出声，问题在插件 `Stream()` 返回的 URL/headers 或 Go→Kotlin 桥接；
若两者都不出声，问题在 Media3/音频设备/权限。

## 阶段 F：车机 Compose UI

正式入口已从冒烟面板切到 `CarScreen`。布局为**横屏三区**：左侧 110dp 导航栏（首页/搜索/本地/设置 + 音源切换）+ 中间内容区 + 底部 88dp 播放条；点击播放条展开全屏"正在播放"页。

| 文件 | 职责 |
|---|---|
| `ui/CarScreen.kt` | 三区布局 + 6 个页面（Home/Browse/Search/Library/Plugins/Settings）+ 音源 chip 条 + 播放条（含队列入口） |
| `ui/NowPlayingScreen.kt` | 全屏播放页：`LYRICS` / `QUEUE` 两个 pane 共用头部与 transport，位置轮询 Media3 |
| `ui/PluginsScreen.kt` | 插件管理页：安装（SAF/repo）、配置表单、卸载确认、更新检查 |
| `ui/Theme.kt` | 深色科技风（深蓝底 + 青色辉光），车机高对比；`TouchTarget=56` |
| `ui/Components.kt` | 封面占位、分区标题、曲目行、磁贴、导航按钮 |
| `ui/MainViewModel.kt` | 单一状态源：订阅 Go 事件 + 调用 Repository，页面/播放/设置/App Link 消费 |
| `data/Models.kt` | music 数据模型 + 容错 JSON 解析 |
| `data/MusicRepository.kt` | 同步 JNI facade 的 suspend 封装，全部走 IO 线程 |

触控目标：导航按钮 72dp、曲目行 64dp、播放条 88dp、音源项 48dp；内容区用 LazyColumn + horizontalScroll，无固定超大尺寸，规避 1920×720 溢出。

> 冒烟面板 `ui/SmokeScreen.kt` 保留但不再是入口，需要时可临时改 MainActivity 的 setContent 调用。

## 模拟器验证（x86_64）

车机 AAR 默认只含 arm64 JNI，x86_64 模拟器会加载失败。已准备多架构方案：

```bash
# 1) 构建 arm64+x86_64 AAR（android-bridge/）
TARGET=android/arm64,android/amd64 AAR_OUT=../build/mygobridge-emulator.aar bash aar.sh
# 2) 构建模拟器 APK（car-app/）
bash build.sh assemble -Pemulator
# 3) 装到已启动的 x86_64 模拟器
adb install -r build/mygocar-emulator.apk
```

`-Pemulator` 会切到多架构 AAR 并放开 x86_64 的 abiFilters。

**环境状态（2026-11-17 已跑通）**：BIOS 打开 Intel VT 后 `HypervisorPresent=True`，`mygo_car_avd` 52 秒完成 boot，分辨率 `2400x1080`（`wm user-rotation lock 1` 强制横屏）。启动、装包、Go 桥（3 个插件）、网络、播放全部实测通过。

一键启动并验证：

```bash
# 终端 1
bash car-app/start-emulator.sh
# 终端 2
APK="$PWD/build/mygocar-emulator.apk" bash car-app/verify-emulator.sh
```

`verify-emulator.sh` 会等待设备、锁定横屏、安装 APK、抓 `logcat`，并把截图存到 `build/mygo_car_emulator.png`。

### 实测通过的能力

| 能力 | 证据 |
|---|---|
| cnradio 直播（中国之声） | `dumpsys media_session` → `state=PLAYING(3)`；`dumpsys audio` → AudioTrack 44100Hz |
| HLS 直连（无 Python/ffmpeg） | `GoMediaOutput: load http://ytcast2.radio.cn/…/index_40639.m3u8 mime=application/x-mpegURL` |
| 媒体键播放/暂停 | `adb shell input keyevent 85` 使 `PLAYING ⇄ PAUSED` |
| demo 本地 `file://` 播放 | `load file:///…/plugins/demo/tone.mp3`，自动续播 `index` 0→1→2 |
| 搜索 | Demo 页输入 `Song` 返回 Song 1/2/3 |
| 全屏播放页 | 封面 + 标题 + 上一首/下一首/播放 + 音量全部在屏内；歌词/队列两个 pane |
| 透明状态栏 | 状态栏可见且透出 App 深蓝底，内容用 `statusBarsPadding()` 下移不被遮挡 |

截图：`build/play_radio.png`、`build/play_demo.png`、`build/page_search3.png`、`build/page_fullplayer2.png`、`build/immersive_home2.png`。

### 模拟器必须知道的四件事

1. **`svc wifi disable` 会丢掉默认路由** → 之后所有 `fetch failed`。恢复：`adb root` + `adb shell ip route add default via 10.0.2.2 dev eth0`，再用 `nc -w 6 <ip> 443` 验证（`ping` 不可靠，Google 的 ICMP 在国内被墙）。
2. `dumpsys connectivity` 里带 `PARTIAL_CONNECTIVITY` 是**正常**的：Android 的联网探测打 `connectivitycheck.gstatic.com`，国内连不上，不影响真实流量。
3. ExoPlayer 只在**创建线程**（主线程）接受命令。Go 从 IO 线程调 MediaOutput，所有播放命令必须投递到主线程。
4. 车机/模拟器页面高度有限（1080p@424dpi ≈ 407dp）：全屏播放页与左栏都按 dp 而不是 px 计算，别写死 `size(220.dp)` 这类会溢出的尺寸。

## 发布（阶段 G）

### 1. 生成签名密钥（只做一次）

```bash
cd car-app
bash make-keystore.sh          # 写入 keystore/mygocar-release.jks + keystore.properties
```

两者都在 `.gitignore` 里。**换掉这个密钥等于换了一个 App**：已安装的用户无法覆盖升级，
所以脚本在密钥已存在时拒绝覆盖，必须 `FORCE=1` 才动手。

`keystore.properties` 缺失时 `assembleRelease` 仍能跑，但产物未签名并会打印警告。

### 2. 构建并校验

```bash
bash build.sh assembleRelease
$ANDROID_HOME/build-tools/34.0.0/apksigner.bat verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

期望：`Verifies` 为真、`v2 scheme: true`、DN 为 `CN=MyGo Car (AGPL fork), O=andy8401`。

### 3. 明文流量策略（改动前务必读完）

release 用 `src/main/res/xml/network_security_config.xml`：**默认禁止明文**，只放行 `radio.cn` 及其子域。

原因是 cnradio 的直播地址由电台 API 返回，是明文 HTTP：

```text
http://ytcast2.radio.cn/110/radios/40639/index_40639.m3u8
```

漏掉这条白名单的失败模式很有迷惑性——**目录页照常显示**（API 走 https），只有点开电台时
`Cleartext HTTP traffic to ytcast2.radio.cn not permitted`，看起来像"插件坏了"。

debug 变体用 `src/debug/res/xml/network_security_config.xml` 全放开，方便把插件指到本地
http 测试服务器。新增会走明文的音源时，两处都要改。

### 4. 仪器化测试

```bash
bash build.sh connectedDebugAndroidTest -Pemulator
```

8 个测试（5 bridge + 3 UI），覆盖主机单测无法触达的部分：JNI 库加载、assets 插件解包到私有目录、
插件运行时在真机 Android 上执行、Compose 布局在真实密度/insets 下可用、插件管理页可达。
`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` 是结果。

> **权限规则必须留着**：`CarUiInstrumentedTest` 里有 `GrantPermissionRule(POST_NOTIFICATIONS)`
> 且 `order = 0`。Gradle 每次跑测试都重装 App，授权会被清掉，MainActivity 首启的权限弹窗会盖住
> Activity，Compose 报的却是「No compose hierarchies found」，看起来像布局崩了。

### 5. AGPL 合规

分发 APK 时必须同时提供 `mygo-music/` + `android-bridge/` + `car-app/` 完整源码，
并保留 `LICENSE` 与 `NOTICE.md`。App 内「设置 → 关于」显示 fork 来源、上游仓库和双版本号。

> **两个版本号别混**：`versionName`（本 App，0.1.0，可随发布递增）与
> Go facade 的 `appVersion`（宿主能力版本，0.1.25）是两回事。后者是插件比对
> `minAppVersion` 的依据（ximalaya-web 要求 ≥ 0.1.20），**调低会让插件拒绝加载**。

## 阶段 H：插件管理 + 详情页

### 插件管理（左侧「插件」页）

阶段 E 的后端能力（`installPluginFromPath/Repo`、`SettingsJSON`、`UninstallPlugin`、
`CheckPluginUpdatesJSON`）早就有了，但一直没有界面，用户拿不到。本轮补入口：

| 操作 | 路径 |
|---|---|
| 从文件安装 | 「选择 zip 文件安装」→ `OpenDocument` → `PluginInstaller.installFromSaf` 复制到 `filesDir` → Go |
| 从仓库安装/更新 | 填 `owner/repo` → 「安装 / 更新」→ `PluginInstaller.installFromRepo` |
| 配置 | 「配置」→ 全屏表单 → `SetSettingJSON`，改完即写 |
| 卸载 | 「卸载」→ 确认框（说明 `plugin-data` 保留）→ `UninstallPlugin` |
| 检查更新 | 右上「检查更新」→ 每行显示「可更新 v1.0 → v1.2」或失败原因 |

表单按 `music.Setting.Type` 渲染：`text` / `password`（掩码）／`boolean`（开关，写 `"true"/"false"`）／
`select`（选项 chips）。**未知 type 回退成文本框**，这样旧 App 也能驱动新插件的 manifest。

卸载当前音源后会自动切到第一个剩余音源并清空书架，否则界面会挂着一个不存在的音源。

### 喜马拉雅「点进去无法播放」的根因

上游把 `Show/Album/Artist/Playlist` 作为**独立导出**，不走 `browse()`。ximalaya-web 的
`browse()` 只服务自己固定的页面，拿到裸 show id 会抛「没有这个喜马拉雅页面」。新增
`android-bridge/facade/detail.go` 的 `OpenDetailJSON(pluginID, kind, id, offset)` 统一入口，
把四种 kind 都归一化成 `music.Browse`，Kotlin 复用现成的渲染管线。

分页：`morePage` 形如 `show:<id>:<offset>`，点「加载更多」原样交回 `openBrowsePage`，
由 `parseDetailPage`（三段 + 第三段是整数）识别；`search:kw`、`category:3` 这类两段 id 不会被误判。

> 音频流本身没问题：免费集无需登录就能拿到 `audiopay.cos.tx.xmcdn.com` 的 mp3 直链。
> 修的是「进不去详情页」，不是「流坏了」。

### 阶段的实测结果（模拟器 2400×1080）

| 项 | 结果 |
|---|---|
| 喜马拉雅详情页 | 打开成功，标题/副标题（`· 192 集`）/单集列表齐全 |
| 播放 | `state=PLAYING(3)`，position 3.1s→9.1s 递增，buffered 131KB |
| 加载更多 | 第 2 页（031–059）接上 |
| 配置保存 | 改 `Python command` → 保存按钮置灰（写入后重载新值） |
| 卸载确认 | 弹框 + 数据保留说明，BACK 取消 |
| 更新检查 | 全本地安装 → 「没有来自仓库的插件，无需检查」 |
| 音源切换 | 顶部 chips：云听 / Demo / 喜马拉雅，同屏全可见 |

## 阶段 I：播放清单 + 歌词 + 透明状态栏

### 播放页：一个 sheet，两个 pane

`ui/NowPlayingScreen.kt` 是新的全屏播放页，`ui.playerPane` 决定中间区显示歌词还是队列；
头部（封面/标题/艺人 + 「歌词 / 播放清单 N / 收起」）与底部 transport（进度条、上一首/播放暂停/下一首、音量）两边共用。

| 位置 | 行为 |
|---|---|
| 点播放条主体 | `vm.openPlayer()` → pane 按 `settings.lyrics` 决定（开则歌词，关则队列） |
| 播放条右侧队列按钮 | `vm.openPlayer(PlayerPane.QUEUE)`，直接进清单 |
| 点队列行 | `vm.playAt(index)` → `Facade.playAt`：只改 `queueAt` 再 `openCurrent`，**不重建队列** |
| 直播电台 | 队列行不可点，底部提示「正在直播：清单由电台推送，只能逐条往下播放」 |
| 点歌词行 | `vm.seekTo(line.start)` 直接跳转 |

### 歌词从哪来

`MusicRepository.lyrics(pluginId, trackId)` → `Facade.lyricsJSON` → `Json.lyrics`。Kotlin **不解析 LRC**：
Go 侧 `lyrics.FromSource` 已经把插件的 timed `Lines` 或纯 LRC 归一化成 `{start,end,text,translation}` 数组，
无 `end` 的行按「下一行 start」补，无 `next` 则补 `start+6s`。

三种状态都要能看：

| Go 返回 | 界面 |
|---|---|
| `supported=true` + lines | 高亮当前行（青色加大），`animateScrollToItem(active-2)` 滚动，下方灰字翻译 |
| `supported=true` + reason | 显示 `reason`（「这首歌没有歌词」/插件的报错文本） |
| `supported=false` + reason | 显示「这个音源没有提供歌词」——**不是错误**，播放器继续跑 |

播放位置只能从 Media3 拿（`PlaybackHolder.current()?.currentPosition`），主线程 `LaunchedEffect` + `delay(250)`
轮询；ExoPlayer 只在创建线程接受调用。

### 状态栏：可见 + 透明

`MainActivity.setUpSystemBars()` 用 `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT) × 2)`
+ `setDecorFitsSystemWindows(false)` + `show(statusBars)` + `hide(navigationBars)` + `isAppearanceLightStatusBars = false`，
内容侧用 `Modifier.statusBarsPadding()`。

- 透明 = `enableEdgeToEdge` 负责；**不被遮挡 = `statusBarsPadding()` 负责**，两者缺一不可。
- 导航栏仍然隐藏：车机屏没有手势条，底部要留给播放条。
- 车机屏 1080p@420dpi 只有 ~411dp 高，状态栏 24–28dp 的占位要按 dp 算，别加固定高度。

### 设置项

「设置 → 显示歌词」开关（默认开），写入 `AppSettings.lyrics`。关掉后：播放页默认落到队列 pane，
头部不再显示「歌词」页签；正在看歌词时关掉会自动切到队列。

### 实测（模拟器 2400×1080，横屏）

| 项 | 结果 |
|---|---|
| 状态栏 | 时间 6:06 + 3G/信号/电池可见，背景与 App 深蓝底同色，标题与内容在状态栏下方 |
| 逐字歌词（demo t2） | 高亮「Word by word」，下行译文「A line sung word by word」 |
| 纯 LRC（demo t1） | 高亮「A plain line」（1000–4000ms），「And another」4000ms 起 |
| 播放清单 | Song 1/2/3，当前项 `♪` 高亮 |
| 队列跳播 | 点 Song 1 → 标题变、进度归零、`state=PLAYING(3)`，队列长度不变 |

截图：`build/h1-home.png`（状态栏）、`h2-lyrics.png`（逐字）、`h3-queue.png`、`h4-jump.png`、`h8-lrc.png`（纯 LRC）。

### 崩溃防线：Go 异常必须有落点

Go facade 的每个方法都可能抛：插件超时（`the plugin didn't answer home in time`）、page id 不对、m3u8 404。
`MusicRepository` 只是 `withContext(IO)` 的薄封装，异常会原样抛给调用方——**而调用方是
`viewModelScope.launch`，未捕获异常直接杀进程**。阶段 I 验证时就踩到了：cnradio 首页超时 → `FATAL EXCEPTION: main`。

两层修复：

1. `MainViewModel` 所有协程走 `guarded.launch`（`viewModelScope.coroutineContext + CoroutineExceptionHandler`），
   异常写进已有的 toast 气泡，并复位 `busy` / `lyricsLoading`。
2. 需要「清空 + 说明」的页面（如 `refreshBrowse`）自己 `try/catch`：超时后清空书架并提示「加载失败：…」，
   否则页面会永远停在「加载中…」。

新增的 UI 协程入口都必须用 `guarded.launch`，别再直接写 `viewModelScope.launch`。

## 阶段 E 边界（未做 / 待真机）

- 媒体键已转发 Go，但方向盘/蓝牙实机键控、音频焦点切换未实测（本机只能编译验证）。
- WebView 登录需真机验证 `until` cookie 判定与各站点登录流程；Cookie 仅存在于本次 WebView 会话，未做跨会话持久化。
- `installPluginFromRepo` 打 GitHub API，真机网络可达性 / 代理环境需验证；本机单测不打真实网络。
- App Link 用自定义 scheme `mygocar://`；正式发布可加 `https` App Link + 域名验证。
- 本地曲库仍为空：`AddLocalFolders` 需要真实路径，SAF 只给 `content://`，Android 侧尚未接 MediaStore。
- 仓库安装/更新只在「无网络可装的真实仓库」下未实测（本轮 3 个插件都是 assets 内置）；安装链路本身已由 SAF 拉起与 Go 单测覆盖。

