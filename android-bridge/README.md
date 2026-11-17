# mygobridge — Android AAR 构建工程

mygo-music 车机移植的 Go 导出层（facade）。把 Go 核心能力封装成 Kotlin 可调用的 AAR。

> 当前状态：**阶段 E 完成**。facade 已接入真实插件运行时（`internal/plugin` + moejs）、应用设置/会话/本地曲库状态层，以及插件安装/卸载/来源/更新检查。11/11 单测通过、AAR 冷构建复现通过、36 项导出 API javap 校验通过。AAR sha256 `228a33d0…35c9`。
> 整体方案见工作区根目录的 `ANDROID_PORT_PLAN.md`。

## 一键构建

```bash
cd android-bridge
bash aar.sh          # 构建 + 校验，无 make 依赖（Windows MinGit 可用）
```

有 `make` 的环境：

```bash
make aar             # 仅构建
make check           # 校验 AAR 内容与 Java API
make info            # 打印工具链路径并校验完整性
```

产物：`../build/mygobridge.aar`

## 目录约定

```
android-bridge/
├── env.sh           # 工具链路径与环境（自包含，不依赖调用者 PATH）
├── build.sh         # gomobile bind 构建
├── check.sh         # AAR 结构 + javap 校验导出 API（含必需 API 存在性检查）
├── aar.sh           # 便携入口：build + check
├── Makefile         # make aar / check / info
├── go.mod           # module mygo-music/bridge + replace ../mygo-music（合法导入 internal 包）
└── facade/
    ├── facade.go    # HostBridge / MediaOutput / Listener 接口 + 全局状态
    ├── plugins.go   # Init/LoadPlugin/ListPluginsJSON/BrowseJSON/SearchJSON（复用 plugin.Load）
    ├── player.go    # PlayQueueJSON/TogglePlay/Next/Prev/SeekMs/SetVolume/Ended（Media3 短路）
    ├── settings.go  # SettingsJSON/SetSettingJSON/HasCapability（插件设置）
    ├── state.go     # AppSettingsJSON/SetSettingsJSON/SaveSessionJSON/RestoreSessionJSON/ClearSession（阶段 C）
    ├── library.go   # AddLocalFolders/LocalFoldersJSON/RescanLibrary/LocalTracksJSON/LocalSearchJSON（阶段 C，复用 internal/local）
    ├── install.go   # InstallPluginFromPath/Repo/UninstallPlugin/PluginOriginsJSON/CheckPluginUpdatesJSON（阶段 E）
    └── facade_test.go # 11 项：3 插件加载 + 设置/会话/曲库 + 安装/卸载/坏路径 + 纯逻辑导出
├── testdata/plugins/ # 固化的 demo / cnradio / ximalaya-web 源码（供单测）
../.toolchain/       # 固化的工具链（go / gomobile / NDK / SDK / JDK）
../build/            # AAR 产物
```

module 名为 `mygo-music/bridge`（原仓库的子路径）配合 `replace mygo-music => ../mygo-music`，这样能在**不改动 AGPL 原仓库**的前提下合法导入 `mygo-music/internal/*`。

## 工具链（已固化到工作区 `.toolchain/`）

| 组件 | 版本 | 说明 |
|---|---|---|
| Go | 1.27.3 | 与 go.mod 一致 |
| gomobile / gobind | x/mobile 20261011 | Go 1.27 需在 go.mod 用 `tool` 指令声明 |
| Android NDK | r27c (27.3.13750724) | clang 18.0.3 |
| Android SDK platform | 35 | 提供 android.jar |
| JDK | 8u512-b09 | gomobile 的 javac 用 `-source/-target 1.8` |

`env.sh` 里所有路径相对工作区解析，构建机无需预装 Go/JDK/NDK。若工具链缺失，`check_toolchain` 会报出具体缺失路径。

## 关键构建参数（可在 env.sh 覆盖）

| 变量 | 默认 | 说明 |
|---|---|---|
| `TARGET` | `android/arm64` | 车机主流架构；可设 `android` 打全架构 |
| `ANDROID_API` | `24` | gomobile 默认 16 不被 NDK 支持 |
| `GOPROXY` | 阿里云镜像 | `proxy.golang.org` 经本机代理 502 |
| `GOSUMDB` | `off` | sumdb 写临时目录被权限拒绝 |
| `AAR_OUT` | `../build/mygobridge.aar` | 产物路径 |

## 导出 API（阶段 E）

Kotlin 实现的接口：

- `HostBridge`：`openURL(url)`、`toast(msg)`、`webSignInJSON(reqJSON) → cookieJSON`
- `MediaOutput`：`load(url, headersJSON)`、`play()`、`pause()`、`seekMs(ms)`、`setVolume(v)`
- `Listener`：`onChangedJSON(stateJSON)`、`onError(source, msg)`

Go 导出的静态函数（对应 Kotlin `facade.Facade.*`）：

| 类别 | 函数 |
|---|---|
| 接线 | `setHostBridge`、`setMediaOutput`、`setListener` |
| 插件 | `init(dataDir) → count`、`loadPlugin(dir, dataDir)`、`listPluginsJSON()`、`hasCapability(id, what)` |
| 浏览 | `browseJSON(pluginID, page)`、`searchJSON(pluginID, query, kind, offset)` |
| 播放 | `playQueueJSON(pluginID, tracksJSON, start)`、`togglePlay()`、`next()`、`prev()`、`seekMs(ms)`、`setVolume(v)`、`ended()`、`stateJSON()` |
| 流 | `resolveStreamJSON(pluginID, trackID, quality)` |
| 插件设置 | `settingsJSON(pluginID)`、`setSettingJSON(pluginID, key, value)` |
| 应用设置 | `appSettingsJSON()`、`setSettingsJSON(patchJSON)`（阶段 C） |
| 会话 | `saveSessionJSON(snapshot)`、`restoreSessionJSON()`、`clearSession()`（阶段 C） |
| 本地曲库 | `addLocalFolders(foldersJSON)`、`localFoldersJSON()`、`rescanLibrary()`、`localTracksJSON()`、`localSearchJSON(query, offset)`（阶段 C） |
| 安装（阶段 E） | `installPluginFromPath(path) → id`、`installPluginFromRepo(owner/repo) → id`、`uninstallPlugin(id)`、`pluginOriginsJSON()`、`checkPluginUpdatesJSON()` |
| 杂项 | `version()` |

> 设计要点：所有字典跨 JNI 一律走 JSON 字符串；所有回调用 Go interface 而非 func 参数——这两点是 gomobile bind 的硬性约束，已由 `check.sh` 的 javap 输出与 36 项 API 存在性检查验证。注意区分**插件设置**（`settingsJSON`，按 pluginID）与**应用设置**（`appSettingsJSON`，全局）。
>
> 安装 API 包装 `internal/plugin` 的 `Open`/`Fetch`/`Pending.Install`/`Remove`，安装后 `reloadPluginLocked` 会关闭旧实例、加载新副本并替换内存列表；`checkPluginUpdatesJSON` 对每个仓库插件单独报错，不因一个失败而全盘失败。两个网络方法在 Kotlin 侧走 `Dispatchers.IO`，不要在主线程调用。

## 单元测试

```bash
bash -c 'source ./env.sh; cd "$BRIDGE_DIR"; go test ./facade -v'
```

11 项测试覆盖：三个真实插件（demo/cnradio/ximalaya-web）通过 `plugin.Discover`/`plugin.Load` 加载成功；`BrowseJSON`/`SettingsJSON`/`StateJSON` 等纯逻辑 API 可用；应用设置与会话快照持久化到注入的 `dataDir`；本地曲库复用 `internal/local` 的增删目录、重扫、浏览、搜索；本地路径安装 → 列表/来源核对 → 卸载 → 更新检查空列表，以及坏路径不污染插件列表。单测走的是与 Android 运行时同一条加载路径。`installPluginFromRepo` 不打真实 GitHub（代理不稳），仅验证编译绑定。
