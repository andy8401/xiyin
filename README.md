# MyGo Car

MyGo Music 的 Android 车机版移植。保留上游 Go 插件核心与原插件生态，用 gomobile 桥接，
Media3/ExoPlayer 承担播放，界面为 Kotlin/Compose 的横屏车机布局。

上游：[andy8401/mygo-music](https://github.com/andy8401/mygo-music) · AGPL-3.0 衍生作品

## 能力

- **插件兼容**：沿用上游插件接口，`cnradio`、`ximalaya-web`、`demo` 三个插件随包内置，
  支持从本地 zip 或仓库链接安装、配置、更新、卸载。
- **登录**：facade 导出 `SignIn/SignOut/Account`；喜马拉雅走 WebView passport 页，
  网易云/QQ 音乐走二维码（`rsc.io/qr` 编码，Compose Canvas 绘制）。
- **播放**：Go 只解析 `music.Stream{URL, Headers}`，Media3 负责下载、解封装与解码；
  支持播放队列、队列内跳播、逐字/LRC 歌词、直播流。
- **MUSE 视觉**：炭黑 `#0F0E0E` / 暖金 `#E8C268` / 纸白 `#F2F2F2` / 深棕 `#2E2620`；
  封面由 Canvas 离线绘制的抽象渐变与声纹图案，另有全屏驾驶专注模式。
- **稳定化**：所有桥接调用统一异常捕获，插件超时不再杀进程。

## 目录

| 路径 | 说明 |
|---|---|
| `android-bridge/` | gomobile facade（Go），导出 44 个 JNI API，含插件管理/登录/歌词/队列 |
| `car-app/` | Android 工程（Kotlin/Compose/Media3），`build.sh` 一键构建 |
| `ANDROID_PORT_PLAN.md` | 完整方案、阶段 A–K 记录、验证证据与踩坑 |

## 构建

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

签名密钥与 `keystore.properties` **不入库**；用 `car-app/make-keystore.sh` 本地生成。

## 测试

- Go 单测：`cd android-bridge && source ./env.sh && go test ./facade`
- API 清单：`cd android-bridge && ./check.sh`（44 API，`CHECK_OK`）
- 仪器化测试：14 项，覆盖插件加载、宿主版本、歌词两态、队列跳播、登录能力位等。

## 许可

AGPL-3.0。完整许可见 `car-app/LICENSE`，修改说明与来源见 `car-app/NOTICE.md`。
