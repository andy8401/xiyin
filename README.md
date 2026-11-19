# 希音（XIYIN）· 车载内容中枢

> **本项目原名 `mygo-music-car`（MyGo Car 车机版），自 2026-11-18 起更名为「希音」。**
> 仓库、Git 历史与代码全部保留，后续所有迭代以「希音」为唯一名称。

希音把已经跑通的车机播放底座，演进为**统一的内容中枢**：
音乐、播客、有声书、电台共用一个插件体系、一个播放器、一套 MUSE 界面，
并在这个底座上叠加语音控制与离线缓存。

上游：[andy8401/mygo-music](https://github.com/andy8401/mygo-music) · AGPL-3.0 衍生作品

---

## 当前发布

| 版本 | 说明 |
|---|---|
| **v0.1.2** | [Release 页面](https://github.com/andy8401/xiyin/releases/tag/v0.1.2) · [下载 APK](https://github.com/andy8401/xiyin/releases/download/v0.1.2/xiyin-release-v0.1.2.apk) · `versionCode=3` · `versionName=0.1.2` |

- 修复车机端“无法检验签名”：APK 同时带 **v1（JAR）+ v2 + v3** 签名，兼容只认 v1 的车机 ROM；证书不变、非 debug。
- APK SHA-256：`96b30256c2a4138eb6f2e4247a9d29c14c7b94c3786f4d728ee1d7f9409c3866`
- 签名核验命令：`apksigner verify --verbose --min-sdk-version 18 <apk>`。默认按 `minSdk=24` 核验不会列出 v1，属误判。

## 当前状态

仓库包含**完整可构建的车机底座**（原 MyGo Car 阶段 A–K 的全部成果）：

| 目录 | 说明 |
|---|---|
| `android-bridge/` | gomobile facade（Go），**44 个 JNI API**：插件管理、登录、详情、播放、队列、歌词 |
| `car-app/` | Android 车机工程（Kotlin/Compose/Media3），`build.sh` 一键构建 |
| `ANDROID_PORT_PLAN.md` | 阶段 A–K 完整方案、验证证据与踩坑记录 |
| `docs/` | 希音阶段的架构设计与路线图（草案） |
| `prototype/` | **可交互车机 H5 原型**（单文件，浏览器直接打开） |
| `apps/car/`、`packages/plugin-sdk/` | 希音后续模块占位，见各目录说明 |

## 已实现能力

- **插件兼容**：沿用上游插件接口；**默认随包内置 `cnradio`（电台）与 `ximalaya-web`（喜马拉雅）**，`demo` 不再随包分发（仍可从 zip / 仓库自行安装）。支持配置、更新、卸载；首次安装解包内置插件，升级不覆盖用户已装插件。
- **在线封面**：专辑 / 曲目 / 节目 / 电台封面优先使用插件返回的 `imageUrl`（Coil 加载），拉取失败或为空时回退本地 `MuseCover` 占位图。
- **本地收藏**：按插件隔离的收藏分类，覆盖频道 / 歌曲 / 歌单 / 节目；列表与播放栏均可收藏，仅存本机 `SharedPreferences`，不接在线账号。
- **车机导航与适配**：左侧栏按插件动态生成分类并带站点图标；本地曲库作为独立音源；16:9 默认居中布局，底部系统栏避让；播放使用 `USAGE_MEDIA` 音频焦点；启动图标为 3:2 横版 MUSE 标。
- **登录**：facade 导出 `SignIn/SignOut/Account`；喜马拉雅走 WebView passport 页，网易云/QQ 音乐走二维码。
- **播放**：Go 只解析 `music.Stream{URL, Headers}`，Media3 负责解码；点播与直播统一流模型，支持队列内跳播。
- **歌词与队列**：逐字/LRC 歌词、播放清单、当前曲高亮。
- **MUSE 视觉**：炭黑 `#0F0E0E` / 暖金 `#E8C268` / 纸白 `#F2F2F2` / 深棕 `#2E2620`；Canvas 离线绘制抽象封面；全屏驾驶专注模式。
- **稳定性**：桥接调用统一异常捕获，插件超时不再杀进程。

## 希音阶段规划

| 阶段 | 目标 | 详见 |
|---|---|---|
| A · 立项与边界 | 定死四类内容范围与合规边界 | [`docs/ROADMAP.md`](docs/ROADMAP.md) |
| B · 底座复用 | 把车机底座抽成可复用壳，插件 SDK 化 | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| C · 新能力 | 播客/有声书音源、语音控制、离线缓存 | 同上 |
| D · 真机验证 | 车机实车验证与发布 | 同上 |

**设计原则**：内容类型由插件声明，不由 UI 从文案猜——这是阶段 J 踩过的坑（UI 写「专辑」而 ximalaya-web 只导出 `show()`），已固化进架构文档。

## 构建与验证

工具链放在工作区 `.toolchain/`，`build.sh` 自行设置 `JAVA_HOME` / `ANDROID_HOME` /
`GRADLE_USER_HOME`，不依赖调用者 PATH。

```bash
cd android-bridge && ./build.sh          # arm64 AAR
cd ../car-app
./build.sh assemble                      # arm64 debug
./build.sh assembleRelease               # arm64 release（需 make-keystore.sh）
./build.sh assemble -Pemulator           # arm64 + x86_64 模拟器包
./build.sh check                         # Kotlin 编译 + lint
./build.sh connectedAndroidTest -Pemulator
```

- Go 单测：`cd android-bridge && source ./env.sh && go test ./facade`
- API 清单：`cd android-bridge && ./check.sh`（44 API，`CHECK_OK`）
- 仪器化测试：14 项，0 失败。
- 发布签名核验必须显式加 `--min-sdk-version 18`，否则 v1 JAR 签名不会被列出；车机 ROM 兼容依赖 v1。

签名密钥与 `keystore.properties` **不入库**；用 `car-app/make-keystore.sh` 本地生成。

## 原型

打开 [`prototype/index.html`](prototype/index.html) 即可看到 MUSE 风格在音乐/播客/有声书/电台
四类内容上的统一浏览与播放体验，无需任何依赖。

## 许可

AGPL-3.0。完整许可见 `car-app/LICENSE`，修改说明与来源见 `car-app/NOTICE.md`。
