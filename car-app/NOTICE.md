# NOTICE — MyGo Car（AGPL-3.0 衍生作品）

## 这是什么

`car-app/` + `android-bridge/` 是 **MyGo Music 的 Android 车机移植分支**，属于
AGPL-3.0 意义上的**衍生作品**，不是独立程序。

| 项 | 值 |
|---|---|
| 上游项目 | MyGo Music — <https://github.com/egoist/mygo-music> |
| 上游版本基线 | `0.1.25` |
| 上游许可 | GNU Affero General Public License v3.0 |
| 本分支维护 | <https://github.com/andy8401/mygo-music>（fork） |
| 本分支 App 版本 | `0.1.0`（`versionName`） |
| 许可 | **AGPL-3.0**，全文见 [`LICENSE`](LICENSE) |

上游代码以**未修改**形式保留在工作区 `mygo-music/`，作为本分支的参考原始副本；
所有移植改动都在 `android-bridge/`（Go 桥）与 `car-app/`（Android UI）里。

## AGPL-3.0 义务

分发本分支的二进制（APK）时，必须同时提供**完整对应源码**，包括：

1. `android-bridge/`（Go facade + 构建脚本）
2. `car-app/`（Kotlin/Compose 工程）
3. `mygo-music/`（上游核心，未修改）
4. 构建工具链版本与构建步骤（见 [`README.md`](README.md) 与 `../ANDROID_PORT_PLAN.md` 第 8 节）

修改本分支后再分发，同样必须开源，且保留本 NOTICE 与 `LICENSE`。

## 相对上游的修改点

上游是 Go + 原生窗口 + moejs 插件运行时的桌面程序；本分支把播放与 UI 换成 Android 组件，
**插件运行时与插件 API 完全沿用上游**，因此现有插件无需修改即可加载（已用 demo / cnradio /
ximalaya-web 三个真实插件验证）。

| 层 | 上游 | 本分支 |
|---|---|---|
| UI | 原生窗口 + 自绘界面 | Kotlin + Jetpack Compose，横屏三区布局（左 rail / 内容 / 播放条） |
| 音频输出 | Go 内解码 + `audio.Player` | 只解析到 `music.Stream{URL, Headers}`，交给 Media3/ExoPlayer 解码 |
| 插件运行时 | moejs（Goja） | **不变**，`internal/plugin` 原样复用 |
| 宿主服务 | 桌面 host（可选 `host.spawn` 起 Python/ffmpeg 转流） | Android 无 `host.spawn`；cnradio 改用插件自带的 `bridge=direct` 分支，HLS 交给 Media3 |
| 应用设置 / 会话 / 曲库 | `mygo.App.Path` | `Init(dataDir)` 注入 Android app 私有目录 |
| 插件安装 | 桌面文件系统 | SAF 选 zip + `mygocar://install?repo=` 深链 |
| 登录 | 桌面 OAuth 回环 | Android WebView + `CookieManager` |
| 系统集成 | 无 | MediaSession（车机方向盘/蓝牙媒体键）、前台播放服务 |
| 标识 | `dev.egoist.music`、`mygo-music://` | `com.andy8401.mygo.car`、`mygocar://` |

**没有复用上游的发布身份**：本分支不含上游的 `updates.github`（`egoist/mygo-music`）、
`updates.publicKey`、macOS `signingIdentity`，也不使用上游的 identifier 与 URL scheme。
发布签名使用本分支自签密钥（`make-keystore.sh` 生成，不入库）。

### 两个容易混淆的「版本号」

| 名称 | 位置 | 含义 | 能否改动 |
|---|---|---|---|
| `versionName` = `0.1.0` | `car-app/app/build.gradle.kts` | 本 Android App 的版本 | 可改，随发布递增 |
| `appVersion` = `0.1.25` | `android-bridge/facade/facade.go` | **宿主能力版本**，插件用它比对 `minAppVersion` | **不可随意改**：ximalaya-web 要求 ≥ `0.1.20`，调低会导致插件拒绝加载 |

## 插件许可

`car-app/app/src/main/assets/plugins/` 内的 demo / cnradio / ximalaya-web 来自各插件作者，
遵循其各自仓库的许可。随 APK 分发前请核对每个插件的许可条款。

## 上游版权声明

```
MyGo Music
Copyright (c) egoist and contributors
Licensed under the GNU Affero General Public License v3.0.
```

本分支的移植部分：

```
MyGo Car (Android head-unit port)
Copyright (c) 2026 andy8401 and contributors
Licensed under the GNU Affero General Public License v3.0.
```
