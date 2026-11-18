# MyGo Music → Android 车机版 拆包与实施计划

> **更名说明（2026-11-18）**：本项目已更名为「希音（XIYIN）」，仓库 `xiyin`。
> 本文中的 “MyGo Car” 均指本项目前身，阶段 A–K 的记录原样保留以维持证据链。

> 生成时间：2026-11-16
> 目标：在**保留现有插件核心与兼容性**的前提下，把 mygo-music 移植到 Android 车机大屏。
> 原则：能不改就不改；只替换运行时不可用的一层（GUI / 音频输出 / 系统服务）。

---

## 0. 结论先行

| 判断 | 结论 | 依据 |
|---|---|---|
| 能否编译 | <mark>**整个项目可在 android/arm64 编译通过** | `CGO_ENABLED=1` + NDK r27c，`go build ./...` exit=0（2026-11-16 实测） |
| 能否 bind | <mark>**facade 模式可生成 AAR** | gomobile bind 实测产出 `facade.aar` 2.2MB，含 `arm64-v8a/libgojni.so` + `classes.jar` |
| 插件核心 | <mark>**3/3 插件已在 moejs 上加载并调用成功** | 模拟 android host，demo/cnradio/ximalaya 全部 Compile→Load→CallFunction PASS |
| 是否需要大拆包 | **不需要** | GUI 传染集中在 `internal/app`；`music`/`plugin`/`i18n`/`lyrics` 是纯 Go，直接复用 |
| 真正的工作 | 建**一个 facade** + **两个接口** + 重写 Compose UI | 见第 2、3 节 |

**核心策略**：不做大拆包。保留 Go 全栈源码原样，只在其上加一层导出 facade，把 mygo 的两个运行时能力（GUI 事件循环、音频输出）用接口替换掉。

---

## 1. 分层处置清单

| 层 / 包 | 处置 | 说明 |
|---|---|---|
| `internal/music` | **原样保留** | 25 个数据 struct + HTTP/HeadersStay，纯 Go |
| `internal/plugin`（moejs） | **原样保留** | 插件运行时、host API、权限模型、数据契约 |
| `internal/i18n`、`internal/lyrics` | **原样保留** | 纯逻辑 |
| `internal/local` | **原样保留**（小改路径注入） | 曲库扫描，目录由参数传入 |
| `internal/player` | **薄改造**（3 个文件） | 抽 `Output` 接口或走 Media3 短路，见第 5 节 |
| `internal/app` | **状态抽离 + UI 重写** | 纯状态逻辑抽出，39 个 UI 文件用 Compose 重写 |
| `mygo` GUI（Window/Dialog/Tray/Theme） | **替换** | Android 无实现，由 Kotlin 侧提供 |
| `mygo/audio` 输出 | **替换** | android 下选 `output_linux.go`（隐含 linux tag），PulseAudio/ALSA dlopen 失败 → 静音 |

### internal/app 文件分类（据实际 import）

| 类别 | 文件 | 处置 |
|---|---|---|
| **A 纯逻辑** | `sources.go`、`request_headers.go` | 整文件抽走；`sources.go` 的 4 处 UI 回调改为注入 |
| **B 混合** | `settings.go`、`session.go`、`cache.go`、`captions.go`、`download.go`、`plugin_install.go`、`artwork.go`、`app.go` | 状态部分抽走，平台部分替换注入 |
| **C 纯 UI/平台** | `ui_*.go` 29 个 + `backdrop.go`/`retro_render.go`/`icons.go`/`menu.go`/`tray.go`/`updates.go`/`websignin.go`/`notch_*.go` | Compose 重写 / 删除 |

> **最大障碍不是 mygo import，而是状态类型定义在 `ui_*.go` 里**：`commentState`（ui_comments.go:16）、`scrubState`（ui_player.go:15）、`suggestState`（ui_suggest.go:14）、`wallState`（ui_wall.go:33）、`playerSig`（ui_notch.go:542）等十余个。抽核心层时需一并迁移或重新定义。
>
> **现成立足点**：`helpers_test.go:366-375` 的 `newTestApp()` + `posts()` 已实现无窗口纯状态构造 app，可直接作为核心层 API 原型。

---

## 2. 目标架构

```
Kotlin / Compose 车机 UI          ← 只做渲染与输入，状态全在 Go
        │  调用（导出方法）
        ▼
新增 facade 包（非 internal）      ← Bridge：启动/插件/Browse/播放/设置，字典走 JSON 字符串
        │
        ├──► Go 核心层（原样保留）  music · plugin/moejs · player · lyrics · i18n · local
        │
        └──► 两个薄接口            HostBridge（URL/登录/提示） · MediaOutput（播放/seek/音量）
                    │  实现
                    ▼
Android 平台实现                  Media3/ExoPlayer · MediaSession · WebView · SAF · App Links
```

### 为什么必须新建 facade（不能直接 bind 现有包）

1. 所有目标包都在 `internal/` 下，外部桥接包不可直接 bind。
2. `music.Source` 接口含 `IsLiked() (map[string]bool, error)`（music.go:437）与 `Stream() (*Stream, error)`，后者含 `Headers map[string]string`（music.go:255）——map 不可 bind，导致整个接口不可绑定。
3. `player.Player` 全部导出字段是 func 类型（`player.go:32-47`），`New(func(func()))`（player.go:104）不可 bind。
4. host 注入 `SetOpenURL/SetWebSignIn/OnSignInPrompt/Remove` 全是 func 参数（host.go:36,65；music.go:394；install.go:664）。

**统一解法**：facade 内所有字典用 JSON 字符串；所有 func 回调改成 Go 接口（Kotlin 实现）。

---

## 3. 端到端实测：gomobile bind 可行（2026-11-16）

### 3.1 实测产物

| 项 | 结果 |
|---|---|
| AAR | `/tmp/bindtest/facade.aar`，**2.2MB** |
| JNI | `jni/arm64-v8a/libgojni.so`（5.6MB） |
| Java 层 | `classes.jar` → `facade.Facade`、`HostBridge`、`MediaOutput`、`Track` |
| 命令 | `gomobile bind -target=android/arm64 -androidapi 24 -o facade.aar ./facade` |

### 3.2 生成的 Java 接口（javap 实测）

```java
public interface facade.HostBridge {
  void openURL(String) throws Exception;
  void toast(String);
  String webSignInJSON(String) throws Exception;
}
public interface facade.MediaOutput {
  void load(String url, String headersJSON) throws Exception;
  void play();  void pause();  void onEnd();
  void seekMs(long);  void setVolume(double);
}
public abstract class facade.Facade {
  static native void setHostBridge(HostBridge);
  static native void setMediaOutput(MediaOutput);
  static native void init(String);
  static native String browseJSON() throws Exception;
  static native void playQueueJSON(String, long) throws Exception;
  static native String version();
}
```

**结论**：设计方案中的接口形态已被 gomobile 实际生成验证，Kotlin 可直接实现 `HostBridge`/`MediaOutput`。

---

## 4. 两个接口的 Go 定义

```go
// HostBridge：Kotlin 提供平台能力，替代 mygo.Shell/Dialog/WebView/Theme
type HostBridge interface {
    OpenURL(url string) error
    WebSignInJSON(reqJSON string) (string, error) // WebSignIn struct → JSON
    Toast(msg string)
    // 后续可加 PickFolderJSON / Confirm / DataDir 等
}

// MediaOutput：Kotlin 用 Media3/ExoPlayer 实现播放，替代 audio.Player
type MediaOutput interface {
    Load(url, headersJSON string) error // 对应 music.Stream{URL, Headers}
    Play()
    Pause()
    SeekMs(ms int64)
    SetVolume(v float64) // 0..1
    OnEnd()              // Go 回调注册点：Kotlin 播完时调用 Go 的 Ended()
}
```

**接线方式**：facade 内部用闭包把接口适配到现有 func 注入点，func 只存在于 Go 内部：

```go
plugin.SetOpenURL(h.OpenURL)                              // host.go:36
plugin.SetWebSignIn(func(req WebSignIn) (string, error) {  // host.go:65
    b, _ := json.Marshal(req)
    return h.WebSignInJSON(string(b))
})
plugin.SetAppVersion("0.1.25")                            // plugin.go:141 — 必须 ≥ ximalaya 要求的 0.1.20
```

---

## 5. 音频输出替换：两套方案，二选一

### 方案 A：保留 Go 解码链，只换输出设备

把 `audio.Player` 抽成 9 方法接口，**改动 3 个文件**：

| 文件 | 改动点 |
|---|---|
| `internal/player/player.go` | 字段 `out *audio.Player`（:65）改接口；`audio.NewPlayer`（:711）改注入点；`audio.SetLatency`（:110）与 `mygo.NowPlaying`（:112-160）抽象 |
| `internal/player/fade.go` | `fader.out`（:24）、`attach`（:49）改接口；`SetVolume` 高频调用（8ms tick）注意跨 JNI 开销 |
| `internal/player/fixture.go` | `Attach(out *audio.Player)`（:39）改接口（测试用） |

接口（9 个方法，代码实际用到，无法再删）：

```go
type Output interface {
    Play(); Pause(); Playing() bool
    Seek(time.Duration); Position() time.Duration; Duration() time.Duration
    SetVolume(float64); OnEnd(func()); Close()
}
```

适用：想保留 Go 的本地文件解码（MP3/FLAC/Ogg/WAV/AAC）、淡入淡出、响度归一化、频谱。

### 方案 B：Media3 直接吃 URL（短路 Go 解码链）— <mark>**车机推荐**</mark>

Go 侧只需保留到 `music.Stream`（`source.go:283-301`），以下环节全部短路：

| 可短路 | 文件:行 |
|---|---|
| 缓存下载 | `cache.go:110-118` |
| HTTP 下载器 | `stream.go:28-136` |
| 格式嗅探 + Go 解码 | `stream.go:334-601` |
| 频谱 | `stream.go:660-777` |
| watched + audio.Player | `starved.go:16-49`、`player.go:708-735`、`fade.go` |

Kotlin 侧：`ExoPlayer.setMediaItem(MediaItem.fromUri(stream.url))` + `setHttpHeaders(stream.headers)`。
额外收益：**cnradio 的 HLS 直播不再需要 Python/ffmpeg 桥接**（`index.js:669` 已有 direct 分支返回 HLS URL，ExoPlayer 原生解 HLS）。

> **落地时踩到的两个细节（2026-11-17 模拟器实测）**
>
> 1. `bridge` 设置必须真的是 `direct`。插件默认值是 `auto`，会先尝试 `host.spawn("python3"/"ffmpeg")`，在 Android 上直接抛
>    `the plugin needs setting up: Live streams need a bridge …`。因此 `facade/platform.go` 在 `Init()` 里对"声明了 `bridge`
>    设置且值为空"的插件写入 `direct`（用户显式选过的值不覆盖），并持久化到该插件的 `settings.json`。
> 2. HLS 必须显式声明 MIME。cnradio 直播地址带签名查询串（`…/x.m3u8?k=…&t=…`），
>    `DefaultMediaSourceFactory` 按**最后一个路径段**推断容器，带 query 时推断失败会按 progressive 音频解析。
>    `MediaOutputImpl.load()` 因此按 `url.substringBefore('?').endsWith(".m3u8")` 显式 `setMimeType(MimeTypes.APPLICATION_M3U8)`。
>
> Referer/UA 不需要额外处理：`plugin.Stream()`（`internal/plugin/source.go:288-299`）已按 manifest 的 `requestHeaders`
> 自动补齐 `*.radio.cn` 的音频请求头，`direct` 模式同样生效。

必须保留：`music.HeadersStay`（fetch.go:81-103）的跨域去 header 策略建议 Kotlin 侧复刻，防 token 泄露。

---

## 6. Facade API 清单（首版）

### Kotlin → Go

| 方法 | 能力 | 对应现有实现 |
|---|---|---|
| `Init(dataDirJSON)` | 初始化：设置注入、加载设置/曲库/插件 | `app.Run`（app.go:323）、`makeSources`（:230）、`boot`（sources.go:181） |
| `BrowseJSON()` | 当前页 Browse 数据 | `a.loadBrowse`（ui_browse.go:30）、`lib.home`（sources.go:83） |
| `SearchJSON(query, kind, offset)` | 搜索 | `player`/`source.Search`（music.go:421） |
| `PlayQueueJSON(tracksJSON, start)` | 播放队列 | `player.go:184` |
| `TogglePlay/Next/Prev/Jump/SeekMs/SetVolume` | 播放控制 | `player.go:444/401/420/434/495/534` |
| `SettingsJSON(pluginID)` / `SetSettingJSON(...)` | **插件**设置读写 | `settings.go:105/119`、`plugin.go:679` |
| `AppSettingsJSON()` / `SetSettingsJSON(patch)` | **应用**设置读写（阶段 C） | 原 `ui_settings.go` 状态，持久化到 `dataDir/app-settings.json` |
| `SaveSessionJSON(snapshot)` / `RestoreSessionJSON()` / `ClearSession()` | 会话快照（阶段 C） | 原 `session.go`，持久化到 `dataDir/session.json` |
| `AddLocalFolders(foldersJSON)` / `LocalFoldersJSON()` / `RescanLibrary()` / `LocalTracksJSON()` / `LocalSearchJSON(q,offset)` | 本地曲库（阶段 C） | 复用 `internal/local.Source`，目录注入 `dataDir/local` |
| `InstallPluginJSON(repoOrPath)` | 插件安装（**未实现**） | `install.go:289/357`、`Pending.Install`（:625） |
| `Ended()` | Kotlin 播放结束回调（方案 B） | 替代 `OnEnd`（player.go:719） |

### Go → Kotlin（通过 HostBridge + 一个 listener）

| 事件 | 现有机制 |
|---|---|
| 状态变化 | `p.Changed func()`（player.go:47）+ `a.changed()`（ui_settings.go:70）→ 改成 `OnChangedJSON(stateJSON)` |
| Toast | `a.toast string`（app.go:96），现有"赋值-消费一次"语义要改成主动回调 `Toast(msg)` |
| 错误/登录失败 | `p.Failed func(source,err)`（player.go:37）→ `a.problem`（sources.go:237）→ `OnErrorJSON` |

---

## 7. 分阶段实施（A–G）

| 阶段 | 内容 | 验证标准 | 状态 |
|---|---|---|---|
| **A 环境固化** | 工具链固化到 `.toolchain/`，写 `env.sh`/`build.sh`/`check.sh`/`aar.sh`/`Makefile` | 一键产出 AAR | <mark>**已完成**（2026-11-16，冷构建复现通过，AAR `303badc3…3013`） |
| **B facade 接入插件** | 新建 `mygo-music/bridge` module（子路径+replace 合法导入 internal），定义 HostBridge/MediaOutput/Listener，接入 `plugin.Load` 真实运行时，实现第 6 节 API | `gomobile bind` + 3/3 插件加载单测 + javap 全 API | <mark>**已完成**（2026-11-16，5/5 单测 PASS，AAR 11MB `3042734f…31a9`） |
| **C 状态抽离** | 应用设置/会话快照/本地曲库三层落地 `state.go`+`library.go`，复用 `internal/local`；`Init(dataDir)` 注入 `dataDir` 替代桌面端 `mygo.App.Path`；空 folders 返回 `[]` 而非 `null` | `go test ./facade -v` 9/9 PASS + `go vet` 通过 + AAR 31/31 API javap 校验 | <mark>**已完成**（2026-11-16，AAR 11MB `bd399ae2…98f`） |
| **D 音频替换** | 按方案 B 实现 MediaOutput，Kotlin 侧接 ExoPlayer + MediaSession | Kotlin 桥接 + Media3 编译进 APK；**真机出声/HLS 直播待设备验证** | <mark>**代码与构建已完成**（2026-11-16，APK 41MB `d36eb6db… ec24d`，minSdk24/arm64） |
| **E 插件接线** | `SetAppVersion("0.1.25")` + 三个 host 回调注入（阶段 B）；Go facade 新增 `InstallPluginFromPath/Repo`、`UninstallPlugin`、`PluginOriginsJSON`、`CheckPluginUpdatesJSON`；Kotlin 侧 SAF zip 安装、`mygocar://install` App Link、WebView 登录、媒体键转发 Go | 11/11 facade 单测 PASS + AAR 36/36 API + APK BUILD SUCCESSFUL | <mark>**代码与构建已完成**（2026-11-16，AAR `228a33d0…35c9`，APK `309e0bbf…09d2`） |
| **F Compose UI** | 车机横屏三区布局（左导航 rail + 内容 + 底部播放条）+ 全屏播放页；Home/Browse/Search/本地/设置 5 页；深色科技风主题、触控目标≥48dp、LazyColumn/滚动兜底防溢出 | Kotlin 编译 + lint 通过、两版 APK 构建成功、静态布局检查无超大固定尺寸 | <mark>**代码与构建完成**（2026-11-16；2026-11-17 模拟器动态验证被 BIOS VT-x 关闭阻塞，见下方说明） |
| **G 回归与发布** | Go 纯逻辑测试 + Android 仪器化测试；替换 AGPL fork 的 identifier/签名/updates repo | 冒烟截图 + 测试数 + AAR hash | <mark>**已完成**（2026-11-17）：Go 14 个单测 + **7 个仪器化测试**（模拟器实跑 0 失败）；自签 release 签名（v2，RSA 4096）并通过 `apksigner verify`；`usesCleartextTraffic=true` 换成 network-security-config（默认禁明文，仅 `radio.cn` 白名单）；LICENSE + NOTICE.md + App 内「关于」区块 |
| **H 插件管理 + 详情页** | 新增「插件」页：安装（SAF zip / GitHub repo）、配置（`SettingsJSON` 表单，text/password/boolean/select 四型）、卸载（确认框）、更新（`CheckPluginUpdatesJSON`）；修喜马拉雅「点进去无法播放」——show/album/artist/playlist 走新 `OpenDetailJSON` 而非 `browse()`；补 `section.links` 解析；音源从 rail 竖排改为内容区顶部横向 chips | Go 4 个 detail 单测 + **8 个仪器化测试**（5 bridge + 3 UI）0 失败；模拟器实测喜马拉雅详情页 `PLAYING`、分页加载更多、配置保存、卸载确认、更新检查 | <mark>**已完成**（2026-11-17） |

> **模拟器验证通过（2026-11-17，BIOS 开启 Intel VT 之后）**：`HypervisorPresent=True`，x86_64 AVD `mygo_car_avd` 52s 完成 boot、`2400x1080` / `ROTATION_90`；APK 安装启动、Go 桥加载 3 插件、cnradio 目录拉取真实数据。
>
> 端到端实测结果：
>
> | 能力 | 结果 | 证据 |
> |---|---|---|
> | cnradio 直播（中国之声） | <mark>**PLAYING**</mark> | `dumpsys media_session` `state=PLAYING(3)`、`dumpsys audio` AudioTrack 44100Hz、截图底部「正在直播：新闻进行时」 |
> | HLS 直连（无 ffmpeg/python） | <mark>**通过**</mark> | `GoMediaOutput: load http://ytcast2.radio.cn/…/index_40639.m3u8 mime=application/x-mpegURL` |
> | 媒体键（播放/暂停） | 通过 | `input keyevent 85` 使 `PLAYING ⇄ PAUSED`，Go `playing` 同步 |
> | demo 本地 `file://` 播放 | 通过 | `load file:///…/plugins/demo/tone.mp3`，AudioTrack 出声 |
> | 队列自动续播 | 通过 | `Ended()` 触发，`index` 0→1→2 |
> | 搜索 | 通过 | Demo「Song」返回 Song 1/2/3 |
> | 全屏播放页 | 通过 | 封面/标题/上一首/下一首/播放/音量全部可见 |
> | 沉浸式全屏 | 通过 | 状态栏隐藏，内容顶到屏幕边缘 |
>
> 本轮修掉的 10 个真实缺陷见第 9 节「模拟器动态验证暴露的缺陷」。
>
> 已知限制：
> - ~~左栏「音源」区在 1080p@424dpi（约 407dp 高）下只能同屏显示 2 项~~ → **阶段 H 已修**：音源改为内容区顶部横向 chip 条，5 个导航项 + 全部音源同屏可见。
> - 「本地曲库」页为空且无添加入口：Android 侧尚未接 SAF/MediaStore，`AddLocalFolders` 需要真实路径而 SAF 只给 `content://`，属未实现功能（非回归）。

---

## 8. 环境与一键构建（阶段 A 已固化）

工具链已从易失的 `/tmp` 固化到工作区 `<workspace>/.toolchain/`，所有路径由 `android-bridge/env.sh` 相对解析，**不依赖调用者 PATH 或系统预装**。

| 组件 | 固化路径（相对工作区） | 版本 / 说明 |
|---|---|---|
| Go | `.toolchain/gotool/go` | 1.27.3（zip sha256 `f26081f4…5fff`） |
| gomobile / gobind | `.toolchain/gotool/bin/` | x/mobile 20261011，需 go.mod 的 `tool` 指令 |
| Android NDK | `.toolchain/android-sdk/ndk/27.3.13750724` | r27c，clang 18.0.3（zip sha256 `27e49f11…2494`） |
| Android SDK platform | `.toolchain/android-sdk/platforms/android-35/android.jar` | API 35 |
| JDK | `.toolchain/jdk8` | 8u512-b09，javac 1.8.0_512 |

**一键构建**（在 `android-bridge/` 目录）：

```bash
bash aar.sh          # 无 make 依赖（本机 MinGit 无 make）；构建 + 校验
# 或
make aar && make check
```

`env.sh` 关键默认值：`GOPROXY=https://mirrors.aliyun.com/goproxy,direct`（proxy.golang.org 经本机代理 502）、`GOSUMDB=off`（sumdb 写临时目录被拒）、`GOFLAGS=-mod=mod`、`TARGET=android/arm64`、`ANDROID_API=24`（gomobile 默认 16 不被 NDK 支持）。

冷构建实测：清空产物后 `bash aar.sh` 重建成功。AAR hash 随阶段演进：

| 阶段 | SHA256 | 体积 | 增量 |
|---|---|---|---|
| A | `303badc3…3013` | 2.2MB | facade 骨架 |
| B | `3042734f978b55bd6c601ad616a393cde88279758fdebbd89ffa9d2d58b031a9` | 11MB | 真实 moejs 插件运行时 |
| C | `bd399ae249b314b61f802913e149103365116e56cccb2d7ef72085074f18e98f` | 11MB | 应用设置/会话/本地曲库状态层，31 个导出 API |
| E | `228a33d0716cda90d03aa9727f0a88a375d31aed498b9a569963fa36292635c9` | 11MB | 插件安装/卸载/来源/更新检查，36 个导出 API |
| F | `883d7487f6573b76e167f57112e47878a0347335c2fe16828419fbb7c9afaf70` | 11MB | 新增 QueueJSON，37 个导出 API（车机 arm64 版） |
| F-emu | `7a14a84aeb80afa86c43f25a84ddf202a6a941993f4b87810568ed9a9ff21e86` | 21MB | arm64+x86_64 多架构（模拟器用） |

---

## 9. 风险与未决项

| 项 | 状态 | 处置 |
|---|---|---|
| `host.spawn` | Android 不可用（**已定论**：全仓仅 cnradio `index.js:607` 一处） | cnradio 走 `bridge=direct` + ExoPlayer HLS；`facade/platform.go` 在 `Init()` 自动写入。**不要伪造实现** |
| OAuth `waitForRedirect` | 本地端口 + 浏览器回调 | 改 App Links / Deep Link，需真机实测（**最大兼容风险**） |
| `webSignIn` Cookie | WebView/Custom Tab 模型不同 | 用 Android WebView `CookieManager`；Custom Tab 读不到 Cookie |
| 音频运行时行为 | 部分实测 | 2026-11-17 模拟器：JNI/插件加载/目录拉取 PASS；出声与 HLS 解码待本轮复测 |
| 语音字幕 | sherpa-onnx 无 Android 库 + 模型约 986MB | 车机不现实，建议云端 ASR 或砍掉 |
| AGPL-3.0 | <mark>**已处置**（2026-11-17） | 本分支未引用上游 `dev.egoist.music`／`updates.publicKey`／`updates.github`／macOS `signingIdentity`（已 grep 确认零引用）；`car-app/LICENSE` 与上游逐字节一致，`NOTICE.md` 列明来源与修改点，App 设置页「关于」显示 fork 标识与双版本号。发布必须随附 `mygo-music/` + `android-bridge/` + `car-app/` 源码 |
| `minAppVersion` | 硬门槛 | 必须 `SetAppVersion("0.1.25")`，否则 ximalaya（要求 0.1.20）加载失败 |
| Android 模拟器 | <mark>**已解决**（2026-11-17） | BIOS 开启 Intel VT 后 `HypervisorPresent=True`，`mygo_car_avd` 52s 启动、`2400x1080` 横屏。此前根因是 BIOS 未开 VT-x，非 BCD/AEHD 问题 |
| 模拟器出网 | <mark>**已解决** | 此前 `svc wifi disable` 导致默认路由丢失 → `fetch failed`；`adb root` + 恢复 `default via 10.0.2.2` 后 TCP 出网正常（`nc` 到 radio.cn:443 `rc=0`）。注意 `PARTIAL_CONNECTIVITY` 是 gstatic 探测被墙所致，不阻断流量 |


### 模拟器动态验证暴露的缺陷（2026-11-17，均已修复）

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 点电台弹「the plugin needs setting up: Live streams need a bridge」 | cnradio 默认 `bridge=auto`，走 `host.spawn("python3"/"ffmpeg")`，Android 无此能力 | `facade/platform.go` 在 `Init()` 里把未设置的 `bridge` 写成 `direct`（用户显式值不覆盖），持久化到插件 `settings.json` |
| 2 | HLS 被当普通音频解析 | `DefaultMediaSourceFactory` 按最后一个路径段推断容器，带签名 query 时失败 | `MediaOutputImpl` 按 `substringBefore('?').endsWith(".m3u8")` 显式 `setMimeType(APPLICATION_M3U8)` |
| 3 | 有 `Load` 但不起播 | Go `openCurrent` 只 `Load` 不 `Play`，`playing=true` 却无声 | `openCurrent` 在 Load 成功后补 `b.media.Play()` |
| 4 | 起播静默失败、`playStationJSON` 抛异常但无崩溃日志 | ExoPlayer 只在创建线程（主线程）接受命令，Go 从 IO 线程调 MediaOutput | `MediaOutputImpl` 全部播放命令投递到主线程 Handler，保持 load→play 顺序 |
| 5 | demo 本地曲目 `ClassCastException: FileURLConnection cannot be cast to HttpURLConnection` | 播放器对**所有** URI 都用 `DefaultHttpDataSource` | 外层改 `DefaultDataSource.Factory(context, httpFactory)`，`file://`/`content://` 自动分流 |
| 6 | 切到 Demo 后标题变了但内容仍是 cnradio 的电台卡 | 旧音源的慢网络响应后到，覆盖新结果 | `refreshBrowse`/`openBrowsePage` 加 `id != activeSourceId` 的丢弃判定；`selectSource` 先清空书架 |
| 7 | 搜索永远为空 | 插件契约是**按 kind 单次返回**（`{ [kind]: [...] }`），Kotlin 传 `kind=""` 取不到数组 | 搜索按 `kind="tracks"` 请求（当前 UI 只渲染 tracks） |
| 8 | 全屏播放页被裁切、控制按钮看不见 | 内容约 570dp，超过 1080p@424dpi 的 ~407dp，居中排列上下都溢出 | 封面改为 `weight(1f)` + `fillMaxHeight().aspectRatio(1f)`，控制区固定高度永不越界 |
| 9 | 状态栏压在内容上 | 未处理系统栏 | 阶段 G 先用 `WindowInsetsControllerCompat` 全隐藏；**阶段 I 改为可见 + 透明**（`enableEdgeToEdge` + `statusBarsPadding()`），见 I.4 |
| 10 | 点电台卡片整个 App 崩溃（`No such page`） | `live:<sid>` 被当作 Browse page id | 上一轮已修：新增 `PlayStationJSON` 走 `StationNext`；`openBrowsePage` 加 `runCatching` |

### 阶段 G：发布面收紧与合规（2026-11-17）

| 项 | 之前 | 现在 | 验证 |
|---|---|---|---|
| 明文流量 | `android:usesCleartextTraffic="true"` 全局放开 | `network-security-config`：`base-config cleartextTrafficPermitted=false`，仅 `radio.cn` 含子域放行；debug 变体单独放开 | release manifest 无 `usesCleartextTraffic`，资源 `res/8G.xml` 含 `radio.cn`；**装 release 后直播仍 `PLAYING`** |
| 签名 | 无 release 签名 | `make-keystore.sh` 生成自签 keystore（RSA 4096 / 有效 30 年），密码走 `keystore.properties`（`FORCE=1` 才覆盖，防误替换断更） | `apksigner verify` → v2 scheme true，DN `CN=MyGo Car (AGPL fork)` |
| 版本号 | `versionName = "0.1.0-stageD"`（阶段名混进版本） | `versionName = "0.1.0"`，改由 `BuildConfig.VERSION_NAME` 读取，杜绝与产物不一致 | `aapt2 dump badging` → `versionName='0.1.0'` |
| 权限 | 声明了未使用的 `READ_EXTERNAL_STORAGE` | 移除（本地曲库尚未实现，不申请无用权限） | release 权限仅 INTERNET／WAKE_LOCK／FOREGROUND_SERVICE(_MEDIA_PLAYBACK)／POST_NOTIFICATIONS |
| R8 | 关闭 | 仍关闭，但写明原因（gomobile JNI + 按名查找，shrink 收益低风险高） | `isMinifyEnabled=false` + 注释 |
| 回归 | 仅 Go 主机侧单测 | 增加 7 个 Android 仪器化测试 | `connectedDebugAndroidTest` → `tests="7" failures="0" errors="0"` |

> **明文白名单是硬约束，不是可选优化**：cnradio 的直播地址由电台 API 返回，形如
> `http://ytcast2.radio.cn/110/radios/40639/index_40639.m3u8`。把 `radio.cn` 从白名单删掉或在
> `base-config` 里保留 false 之外的写法，失败模式是直播全部报 `Cleartext HTTP traffic … not permitted`，
> 而**目录页仍然正常**（API 走 https）——很容易误判成"插件坏了"。


---

### 阶段 H：插件管理与详情页（2026-11-17）

#### H.1 插件管理：后端早就齐了，缺的是 UI

阶段 E 已经把四个后端能力做进 facade，但直到阶段 H 才有界面，等于用户拿不到。
本轮补的是**入口**，不是能力：

| 能力 | Go 侧（阶段 E 已有） | 本轮新增 UI |
|---|---|---|
| 安装（本地 zip） | `InstallPluginFromPath(path)` | 「选择 zip 文件安装」→ `OpenDocument` → 复制到私有目录 → 交 Go |
| 安装 / 更新（仓库） | `InstallPluginFromRepo(repo)` | 「owner/repo」输入框 + 「安装 / 更新」按钮 |
| 配置 | `SettingsJSON(id)` / `SetSettingJSON(id,key,value)` | 全屏表单，按 `type` 渲染 text / password / boolean / select |
| 卸载 | `UninstallPlugin(id)` | 「卸载」→ 确认框（明确说明 plugin-data 保留） |
| 更新检查 | `CheckPluginUpdatesJSON()` | 右上「检查更新」，每行显示「可更新 v1.0 → v1.2」或失败原因 |

设计要点：

- **`Setting.Type` 四型必须都渲染**。插件契约里 `Type` 是 `text`（默认）／`password`／`boolean`／`select`，且值一律是字符串（boolean 写 `"true"/"false"`）。未知 type 回退成文本框，这样旧 App 也能驱动新插件的 manifest。
- **安装冲突即覆盖**。同名插件重装等于升级，`plugin-data` 不删，登录态与设置自动恢复；卸载同理保留数据。
- **仓库是更新的唯一来源**。本地 zip 装的插件没有 `origin.json`，`CheckPluginUpdatesJSON` 会跳过它并在 UI 上标「本地安装」，避免给出「无法更新」的误导。
- **卸载后必须处理当前音源消失**：若卸载的正是 active source，书架要清空并切到第一个剩余音源，否则界面会挂着一个不存在的音源标题。

#### H.2 喜马拉雅「点进去无法播放」：真因不是流坏了

现象：点任何专辑/节目卡都没反应，也没有错误日志（Go 侧 listener 根本没被调用）。

诊断（临时 Go 测试打印真实返回，定位后已删除）：

```text
first show id = "73103039"
show browse(73103039): not supported by this source: 没有这个喜马拉雅页面
```

根因：**上游把 `Show/Album/Artist/Playlist` 作为独立导出，不走 `browse()`**。
ximalaya-web 的 `browse()` 只服务自己固定的页面（`discover`/`rank`/`categories`/`category:N`/`search:kw`…），
拿到一个裸的 show id 只会抛「没有这个喜马拉雅页面」。而 Kotlin 侧把卡片 id 直接当 Browse page id 传了。

同一支诊断还证明**音频流完全正常**——免费集无需登录即可拿到 mp3 直链：

```json
{"url":"https://audiopay.cos.tx.xmcdn.com/download/.../xxx.mp3?sign=...&buy_key=FM...",
 "type":"mp3","quality":"high","size":3026695,
 "headers":{"Referer":"https://www.ximalaya.com/","User-Agent":"Mozilla/5.0 ..."}}
```

即「无法播放」是**进不去详情页**，不是流坏了。

修复：新增 `android-bridge/facade/detail.go`

```go
// kind ∈ show | album | artist | playlist，统一归一化成 music.Browse
func OpenDetailJSON(pluginID, kind, id string, offset int) (string, error)
```

四个转换函数 `showPageBrowse` / `albumPageBrowse` / `artistPageBrowse` / `playlistBrowse`
把 `music.ShowPage` 等转成 `music.Browse`，这样 Kotlin 复用现成的渲染管线，不需要第二个解析器。

分页：`showPageBrowse` 在 `page.More` 时生成 `morePage = "show:<id>:<offset+len(episodes)>"`，
UI 的「加载更多」把它原样交回 `openBrowsePage`，由 `parseDetailPage` 识别为详情请求（三段且第三段是整数）
再走 `openDetail`。`search:kw` / `category:3` 这类两段 id 不会被误判。

#### H.3 顺带修掉的两个「看不见」问题

| 问题 | 后果 | 修复 |
|---|---|---|
| `section.links` 在 Kotlin 侧被整个丢弃 | 喜马拉雅的「按分类浏览」「热门搜索」入口**根本不存在**；show 的「简介」也渲染不出来 | `Json.section()` 解析 `links` → `BrowseItem.Kind.LINK`；无 page 的 link（纯文本简介）单独渲染成段落 |
| 音源列表塞在左 rail 的 `weight(1f)` 里 | 5 个导航项后 rail 只剩约 20dp，**音源实际不可达**（无法切音源） | 音源改为内容区顶部横向 chip 条，占宽不占高 |

另外给三个覆盖层（播放页 / 插件表单 / 卸载确认）补了 `BackHandler`：
此前按返回键会直接退出 App，表单还留在窗口外。

#### H.4 阶段 H 的验证数据

| 项 | 结果 |
|---|---|
| Go 单测 | `go test ./facade` **PASS**（新增 `detail_test.go` 4 个：show / album / 非法入参 / 四种 kind 齐备） |
| AAR 校验 | `check.sh` → **39 个 API** 全部 ok（新增 `openDetailJSON`），`CHECK_OK` |
| 仪器化测试 | `connectedDebugAndroidTest` → **8 tests / 0 failures**（5 bridge + 3 UI，新增 `pluginManagerReachableAndListsBundledPlugins`） |
| 喜马拉雅详情页 | 打开成功：标题「【新评书】兴安岭怪谈…」、副标题「无心方丈故事会 · 192 集」、单集列表 |
| 喜马拉雅播放 | `state=PLAYING(3)`，position 3.1s → 6.1s → 9.1s 递增，buffered 131KB |
| 分页 | 点「加载更多」后第 2 页（031–059）接上，`more` 仍为 true |
| 配置保存 | 改 `Python command` → 「保存」置灰（写入后表单重载新值） |
| 卸载确认 | 弹出「卸载 云听 · 央广在线收音机」+ 数据保留说明；BACK 取消 |
| 更新检查 | 3 个插件全是本地安装 → 提示「没有来自仓库的插件，无需检查」 |
| SAF 安装入口 | 点击后 `com.google.android.documentsui/.picker.PickActivity` 正常拉起 |
| cnradio 回归 | 中国之声 `PLAYING(3)`，底部「正在直播：正午60分」 |

> **测试陷阱（值得记住）**：Gradle 每次 `connectedAndroidTest` 都会重装 App，
> 重装会清掉 `POST_NOTIFICATIONS` 的授权，于是 MainActivity 首启的权限弹窗盖住 Activity，
> Compose 报的是「No compose hierarchies found in any window」，看起来像布局崩了，其实是系统对话框。
> 修法是给 UI 测试加 `GrantPermissionRule`（`order = 0`，早于 activity 启动规则）。



### 阶段 I：播放清单、歌词、透明状态栏（2026-11-17）

#### I.1 歌词：上游链路早就齐了，Android 侧一行没接

`music.Lyrics`、`internal/lyrics`（`FromSource` / `Build` / `Lang` / `Index`）、`plugin.Plugin.Lyrics(id)`、`p.Can("lyrics")`
全都在上游，缺的只是一个给 Kotlin 的出口。

新增 `android-bridge/facade/lyrics.go`：

```go
// → {"lines":[{"start":1000,"end":4000,"text":"…","translation":"…"}],
//    "lang":"…","supported":true,"reason":"…"}
func LyricsJSON(pluginID, trackID string) (string, error)
```

三条设计约束：

1. **Kotlin 不解析 LRC**。`lyrics.FromSource` 优先用插件给的 timed `Lines`，否则解析 LRC；无 `End` 的行补
   `min(At+6s, 下一行 At)`；无 `Words` 的行用 `SpreadWords` 均分。UI 拿到的永远是一条规整的时间轴，
   高亮判断就是一次 `indexOfLast { it.start <= position }`。
2. **「没有歌词」不是错误**。`p.Can("lyrics")` 为 false → `supported=false` + `reason`（音源不提供歌词）；
   导出在但这首取不到 → `supported=true` + `reason`。两条路都不返回 error，播放器继续跑，歌词区只显示原因，
   不进异常分支。
3. **只有 demo 插件导出 `lyrics`**（`t1` 纯 LRC、`t2` 逐字 `lines`）。cnradio / ximalaya-web 落到第一条路径，
   UI 显示「这个音源没有提供歌词」而不是一直转圈。

> **播放位置在 Media3，不在 Go**。facade 里没有 `position` 字段，位置只能从
> `PlaybackHolder.current()?.currentPosition` 取。ExoPlayer 只能在创建它的线程访问，所以用主线程
> `LaunchedEffect` + `delay(250)` 轮询，而不是每帧去 Go 拉一次。

#### I.2 播放清单：跳播不能复用 `PlayQueueJSON`

`PlayQueueJSON(plugin, tracks, index)` 会**重建整个队列**并重新 `stream()`，直播时还会重新拉片段。
队列里换一首歌完全不需要这套。

新增 `PlayAt(index)`：只改 `b.queueAt` 再 `openCurrent(p)`，队列原样保留。两条拒绝：

| 入参 | 行为 | 原因 |
|---|---|---|
| `index < 0` 或 `>= len(tracks)` | 报 `queue position out of range` | 越界是调用方 bug，静默 clamp 会播错歌 |
| `stationId != ""` | 报 `a live station only plays its current segment` | 直播队列是滚动窗口，index 指向的片段可能已被替换 |

#### I.3 播放页状态从 `remember` 提到 ViewModel

之前 `showNowPlaying` 是 `CarScreen` 里的局部 `remember`，播放条上的按钮无法「直接打开某一页」。
改为 `playerOpen: Boolean` + `playerPane: PlayerPane{LYRICS, QUEUE}`，`openPlayer(pane)` 在未指定时按
`settings.lyrics` 决定默认页——所以关掉歌词后点播放条直接落到队列。

`"track"` 事件从通用刷新里拆出来单独处理（换歌必须重取歌词），并用 `lyricsTrackId` 丢弃迟到的响应：
快速连点下一首时，先发出的请求可能后返回，不校验就会把上一首的歌词贴到当前曲目上。

#### I.4 状态栏：从「全隐藏」改成「可见且透明」

`goImmersive()` 会 `hide(systemBars())`，阶段 I 换成 `setUpSystemBars()`：

```kotlin
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
)
WindowCompat.setDecorFitsSystemWindows(window, false)
WindowInsetsControllerCompat(window, window.decorView).apply {
    show(WindowInsetsCompat.Type.statusBars())
    hide(WindowInsetsCompat.Type.navigationBars())
    isAppearanceLightStatusBars = false   // 深色底 → 亮色图标
}
```

内容用 `Modifier.statusBarsPadding()` 下移。**这两件事必须分开做**：`enableEdgeToEdge` 只负责让系统栏背景变透明，
`setDecorFitsSystemWindows(false)` 会让内容顶到时钟图标底下——能透出 App 自己的深蓝底是对的，
内容被压住是错的，`statusBarsPadding()` 才是那一步。

导航栏继续隐藏：车机屏没有手势条的位置，底部要留给播放条。

#### I.5 顺带修掉的崩溃：插件超时会把整个 App 打死

验证阶段 I 时切回 cnradio（首页 browse 要联网），模拟器上直接**整进程崩溃**：

```text
FATAL EXCEPTION: main
go.Universe$proxyerror: the plugin didn't answer home in time
    at facade.Facade.browseJSON(Native Method)
    at com.andy8401.mygo.car.data.MusicRepository$browse$2.invokeSuspend(MusicRepository.kt:27)
```

根因不是插件，是**异常没有落点**：`MusicRepository` 把 Go 的异常原样抛给调用方，而调用方是
`viewModelScope.launch`——`launch` 内未捕获的异常交给主线程默认处理器，**直接杀进程**。
所以「电台接口慢 10 秒」= App 崩，在车上就是开着车突然没声音、界面消失。

修复两层：

1. `MainViewModel` 的 30 个协程入口统一改成 `guarded.launch`（`viewModelScope.coroutineContext + CoroutineExceptionHandler`），
   异常落到已有的 toast 气泡，`busy` / `lyricsLoading` 一起复位。
2. `refreshBrowse()` 单独 `try/catch`：超时后清空书架并给出「加载失败：…」，否则页面会永远停在「加载中…」
   （只靠 guard 的话，异常被吞了但 UI 不知道要结束加载态）。

`openBrowsePage` 本来就是这个写法（`runCatching` + 错误气泡），这次把同一约定补齐到其余入口。

验证：同一条慢网络路径下 `logcat -b crash` 无新 FATAL，App 存活；cnradio 首页约 14s 后正常渲染
「Cloud Radio / National stations」。

| 项 | 结果 |
|---|---|
| 状态栏 | 时间 6:06、3G／信号／电池齐全，背景与 App 深蓝底同色（透明生效），标题与内容区在状态栏下方未被遮挡 |
| 歌词（逐字） | Song 2 → 高亮行「Word by word」青色加粗，下方灰色译文「A line sung word by word」 |
| 歌词（纯 LRC） | Song 1 → 高亮「A plain line」（1000–4000ms），下一行「And another」4000ms 起，证明 `end` 补全生效 |
| 播放清单 | Song 1/2/3，当前项 `♪` + 高亮 |
| 队列跳播 | 点 Song 1 → 标题变 Song 1、进度归 0:04、`state=PLAYING(3)`，队列长度不变（未重建） |

---

### 阶段 J：登录唤起、专辑页与搜索结果（2026-11-17）

三个现象根因各不相同，都不是 WebView 坏了：

**J.1 登录页唤不起来 —— facade 从未导出登录 API**

上游 `internal/plugin/source.go` 本来就有 `SignIn()` / `SignOut()` / `Account()` 和
`OnSignInPrompt()`，但 facade 的导出面里一个都没有，Kotlin 无处可调。两条登录路径宿主
其实都已具备，缺的只是桥：

| 路径 | 音源 | Go 侧 | Kotlin 侧 |
|---|---|---|---|
| `host.webSignIn` → passport 页 | 喜马拉雅 | `p.SignIn()` 触发宿主回调 | 已有 `SignInCoordinator` + `SignInActivity`，实测可直接复用 |
| `host.signInPrompt` → 二维码 | 网易云、QQ 音乐 | `OnSignInPrompt` 收到 `music.SignInPrompt{QR,Text}`，用 `rsc.io/qr` 编成矩阵 | 新增 `SignInPromptSheet`，`Canvas` 画方块 |

新增 `android-bridge/facade/account.go`，导出 `SignInJSON` / `SignOutJSON` / `AccountJSON`。
要点：`SignInJSON` 阻塞至多 5 分钟，用 `signingIn` 防止连点开出两个提示；
二维码不能过 JNI 传二维数组，统一编码成 `[]string`（每行 `"0"/"1"`）；
`ListPluginsJSON` 附带 `signIn`/`account` 能力位，插件卡片只在支持时才显示「登录」。

**构建坑（已固化）**：gomobile 会把导出函数的 Go doc 注释原样抄进生成的 Java，
javac 在中文 Windows 控制台按 GBK 解码。注释里写「网易云 / QQ音乐」会导致
`unmappable character for encoding GBK`，AAR 构建失败。**导出注释必须纯 ASCII**；
`fmt.Errorf("这个音源不需要登录")` 这类字符串字面量不经过 javac，不受影响。

**J.2 专辑页打不开 —— UI 文案与插件导出名不是一回事**

ximalaya-web 只导出 `show()`（`Can("album")==false`），而首页每张卡片写作「专辑」。
旧 `OpenDetailJSON(kind="album", id)` 直接调 `p.Album()` → `not supported by this source`。
修复为按 `detailCandidates()` 依次尝试各详情导出，都不支持时用 `p.Browse(id)` 兜底
（云听的 `playback:<id>` 节目列表正是这条路）；未知 kind 仍明确报错，避免把调用错误
悄悄吞掉。

第二个坑是搜索：`Json` 只解析 `tracks`，`albums/shows/playlists/artists` 全被丢掉，
而喜马拉雅的 kind 名是 `shows` 不是 `albums`。改成一次搜索分别查 `tracks` 与 `shows`
（`shows` 为空才退回 `albums`），并把「专辑与节目」区排在歌曲列表之前——放在 30 条
歌曲之后等于看不见。

**J.3 卡片被压扁 —— 411dp 高的车机窗口头部吃掉了列表**

搜索页原本有独立「搜索」大标题 + 带浮动 label 的输入框 + 音源条，共约 450px，
`LazyColumn` 只剩 235px 视口；横向卡片行被压到 118px，封面下的标题只剩 18px 高。
去掉重复标题（左侧 rail 已高亮「搜索」），输入框改 placeholder 并固定 56dp，
卡片固定 `TileHeight=116dp` / `TileCover=88dp`，分区标题上下留白从 8dp 收到 4dp。

**J.4 多架构 AAR 绕法（本轮再次验证）**

`TARGET=android/arm64,android/amd64` 一次性 bind 会挂死（clang CPU 归零、临时目录停止
增长，已出现 3 次）。可行做法是分别打两个单架构 AAR，再用 `zipfile` 合并 JNI 目录；
`classes.jar` / `AndroidManifest.xml` 完全相同，去重即可。另外 `AAR_OUT` 必须传绝对路径，
相对路径会落到 `android-bridge/build/`，gradle 仍读旧包，表现为 `Unresolved reference 'playAt'`。

| 验证项 | 结果 |
|---|---|
| Go 单测 | `go test ./facade` 全 PASS（登录 5 项 + 详情回退 + 既有回归） |
| API 清单 | `check.sh` **44 个 API**，`CHECK_OK` |
| Kotlin 编译与 lint | `build.sh check` → `BUILD SUCCESSFUL` |
| 仪器化测试 | `connectedAndroidTest -Pemulator` **14/14 通过，0 失败** |
| 登录按钮能力判定 | `build/j2-plugins.png`、`j3-plugins2.png`：Demo 与喜马拉雅显示「登录」，云听（无 `signIn` 导出）不显示 |
| 唤起登录页 | `build/j4-signin.png`：`topResumedActivity=com.andy8401.mygo.car/.bridge.SignInActivity`，喜马拉雅 passport 二维码页；`j5-signin-cancel.png`：BACK 取消后无残留 |
| 喜马拉雅专辑页 | `build/j7-xm-detail.png`：「【免费】神医下山…」/「钰音音 · 666 集」/「第 1 章 下山」 |
| 云听节目列表 | `build/j11-cn-replay2.png`、`j12-cn-progs.png`：Replay → 中国之声 → 17 条节目 +「播放全部」 |
| 搜索专辑并进详情 | `build/k8-nba-final.png`：「专辑与节目」卡片完整显示（聊聊nba／K话NBA／爱聊NBA／Michael聊NBA／NBA集锦／NBA原声）；`k9-nba-detail.png`：点卡片进「NBA原声 / 麦之英 · 16 集 / 1118韦德赛后采访」 |
| 首页卡片同步修复 | 卡片的「中国之声」「经济之声」「国家」「安徽」文字不再被裁 |

---

### 阶段 K：MUSE 视觉重设计（2026-11-17）

设计稿来自资料库《音乐播放器 UI 重设计.pdf》，是一套名为 **MUSE** 的视觉识别系统：
炭黑 `#0F0E0E`、暖金 `#E8C268`、纸白 `#F2F2F2`、深棕 `#2E2620`，
核心符号是「声源扩散」（一颗实心声源 + 三层向右衰减的声波），
主图标圆角锁定 22.5%。相比原来的深蓝+青色，这是整体换肤，不是局部调色。

| 设计稿页 | 落地实现 | 文件 |
|---|---|---|
| 视觉识别系统 | `CarColors` 改为四色板；旧 `Cyan`/`CyanDim` 保留为 getter 别名，避免一次改完所有调用点；`MaterialTheme` 色板/圆角（large 22dp）同步 | `ui/Theme.kt` |
| 播放页（三栏） | 左大封面 / 中「歌名 + 暖金进度 + 白色大播放键 + 音质胶囊」/ 右深棕队列面板（当前曲暖金高亮） | `ui/NowPlayingScreen.kt` |
| 音乐库（卡片网格） | 4 列改 5 列、横向抽象封面、下方标题；封面由 `Canvas` 画渐变 + 声纹/波浪/斜切/山脊四种图案，完全离线 | `ui/Components.kt`、`ui/CarScreen.kt` |
| 歌词页 | 深棕→炭黑渐变底、左侧小封面、右侧当前行纸白大字 + 暖金译文、底部极简进度与播放键 | `ui/NowPlayingScreen.kt` |
| 驾驶专注模式 | 新增 `UiState.focusMode` 与全屏页：纯黑、左上暖金胶囊、右上退出、居中歌名与大播放键、底部金色进度；覆盖在所有弹层之上 | `ui/MainViewModel.kt`、`ui/CarScreen.kt`、`ui/NowPlayingScreen.kt` |
| 左导航 | 110dp 文字栏改 64dp 图标条（声纹/音符/电台/麦克风/均衡器），10sp 小字保留可访问性 | `ui/CarScreen.kt`、`ui/Components.kt` |

**车机适配的主动偏差**：设计稿按大屏绘制（4 列、封面比例 1.35、30sp 大标题），
实际模拟器可用高度只有 411dp；照搬会让卡片标题被底部播放条裁掉。
因此改为 5 列、封面比例 1.9、页面标题降一档到 `titleLarge`、导航文字 10sp。
视觉语言保持一致，但所有文字都必须完整可见。

| 验证项 | 结果 |
|---|---|
| Kotlin 编译 + lint | `build.sh check` → `BUILD SUCCESSFUL` |
| 仪器化测试 | `connectedAndroidTest -Pemulator` **14/14 通过，0 失败** |
| 首页 | `build/p1-home.png`：黑金配色、图标条、抽象封面，「中国之声 / 经济之声 / 音乐之声 / 中国交通广播」标题完整 |
| 播放页 | `build/p3-queue.png`：三栏具备，音质胶囊「无损 24/96 / 空间音频 / 输出·车载扬声器」完整，队列当前曲高亮 |
| 歌词页 | `build/p2-queue.png`（打开播放页默认 pane）：深棕渐变底，封面不再压住切换标签 |
| 驾驶专注模式 | `build/p5-focus.png`：与设计稿一致的极简全屏页，可从播放页「专注」进入、右上退出 |

---

## 产物与验证汇总

| 产物 | 路径（相对工作区） |
|---|---|
| P0 插件加载验证器 | 临时区 `/tmp/p0verify/verifyapp/`（含 3 插件源码 + android/arm64 可执行文件） |
| 两插件源码 | 临时区 `/tmp/plugin-audit/` |
| **AAR 构建工程** | `android-bridge/`（`env.sh`/`build.sh`/`check.sh`/`aar.sh`/`Makefile`/`facade/`） |
| **AAR 产物** | `build/mygobridge.aar`（阶段 I 复核，sha256 `7475b16fc2cccdb42dc9326411d94bf66722c68607e2a81467393dc85f5513d1`，**44 API**，11MB，arm64-v8a） |
| **Android 仪器化测试** | `car-app/app/src/androidTest/…`（**14 个测试**，模拟器 `connectedDebugAndroidTest` **14/0 失败**：3 插件加载（断言改为 ≥3，因为 `filesDir/plugins` 会跨重装保留）／宿主能力版本 0.1.25／cnradio `bridge=direct`／demo 本地播放到 playing／空电台 id 抛错／**lyrics 两条形态（LRC / 逐字）**／**无歌词音源不抛错**／**playAt 跳播 + 越界拒绝**／左侧 rail／设置页 AGPL／插件管理页／**设置页「显示歌词」默认开启**／**登录能力位（cnradio 无 signIn）**／**无账号 account 返回 signedOut**） |
| 插件/状态/安装/歌词单测 | `android-bridge/facade/*_test.go`（**24 个测试全 PASS**：3/3 插件加载 + 设置/会话/曲库 + 安装/卸载 + QueueJSON + 电台入口 + `platformDefaults`；阶段 I 新增 `lyrics_test.go` 4 个（LRC／逐字 lines／无 lyrics 导出／非法入参）与 `player_test.go` 2 个（队列内跳播／越界拒绝）；2026-11-17 `-count=1` 复跑） |
| 固化工具链 | `.toolchain/`（go / gomobile / NDK / SDK / JDK） |
| 方案文档 | `ANDROID_PORT_PLAN.md`（本文件） |
| **车机 Android 工程** | `car-app/`（`build.sh` 一键构建，README 含冒烟步骤） |
| **APK 产物（车机 debug）** | `build/mygocar-arm64.apk`（sha256 `b030f79c3ccdd754fe93a1241c39afa5401fbb9d25fd6776b7069e2943b73d65`，48M，arm64 单架构 + Compose 车机 UI，透明状态栏 + 播放清单/歌词） |
| **APK 产物（车机 release，签名）** | `build/mygocar-arm64-release.apk`（sha256 `402898e86dbf29eedd62f75e547d5e170d20c77c9914073dce4693051687d3e2`，42M，v2 签名 RSA 4096（`CN=MyGo Car (AGPL fork)`），`versionName 0.1.0`，严格明文策略，`lintVitalRelease` 通过） |
| **APK 产物（模拟器 debug）** | `build/mygocar-emulator.apk`（sha256 `f50139fdb900af5d19aa72def461a0b77b7acccbce88b5621b4b700cf7fcfc05`，77M，arm64+x86_64） |
| **签名密钥脚本** | `car-app/make-keystore.sh`（生成自签 keystore + `keystore.properties`，两者均不入库；`FORCE=1` 才覆盖） |
| **AGPL 合规文件** | `car-app/LICENSE`（AGPL-3.0 全文，与上游逐字节一致）、`car-app/NOTICE.md`（fork 来源／修改点／双版本号说明） |
| **AAR（模拟器多架构）** | `build/mygobridge-emulator.aar`（sha256 `10f0fa6d4e4b3947e981545d9e17738a710a4758b3088e241a661aefd7f0932f`，23M，含 arm64-v8a + x86_64 JNI） |
| **模拟器脚本** | `car-app/start-emulator.sh`（支持 `AVD`/`ACCEL` 覆盖）、`car-app/verify-emulator.sh`（支持 `APK` 覆盖、等待设备、强制横屏、logcat/截图） |
| **当前动态验证状态** | <mark>**阶段 I 端到端跑通**（2026-11-17）：状态栏可见且透明（时间/信号/电池齐全，内容不被遮挡）；歌词两态正常（逐字高亮 + 译文、纯 LRC 补 `end`）；播放清单列 3 首并标 `♪`；点队列行跳播 `state=PLAYING(3)` 且队列未重建；关掉「显示歌词」后播放页默认落到队列 pane 且不显示「歌词」页签；插件超时不再崩进程（`logcat -b crash` 无新 FATAL）。截图：`build/h1-home.png`（状态栏）、`h2-lyrics.png`（逐字）、`h3-queue.png`、`h4-jump.png`、`h8-lrc.png`（纯 LRC）、`i5-lyrics-off.png`（开关关闭 + toast）、`i8-queue-default.png`（关歌词后默认队列）、`i2-timeout.png`（超时后首页正常）；<mark>**阶段 J 端到端跑通**（2026-11-17）：喜马拉雅登录页经 `SignInActivity` 唤起并可取消；喜马拉雅与云听专辑/节目页可打开；搜索「专辑与节目」卡片完整显示，点卡片进详情页；首页卡片标题不再被裁。截图：`j4-signin.png`、`j5-signin-cancel.png`、`j7-xm-detail.png`、`j12-cn-progs.png`、`k8-nba-final.png`、`k9-nba-detail.png`；<mark>**阶段 K MUSE 重设计端到端验证**（2026-11-17）：首页/播放页/歌词页/驾驶专注模式四页均按设计稿落地，卡片与音质标签无裁切，14/14 仪器化测试通过。截图：`p1-home.png`、`p3-queue.png`、`p2-queue.png`、`p5-focus.png` |
| 工作日志 | `.workbuddy/memory/2026-11-16.md`、`.workbuddy/memory/2026-11-17.md` |
