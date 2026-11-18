# 希音 · 架构设计（草案）

## 分层

| 层 | 职责 | 复用 / 新增 |
|---|---|---|
| 插件层 | 音源接入：搜索、详情、播放流、章节列表、账号 | 复用 mygo-music 插件接口；新增播客/有声书适配器 |
| 桥接层 | gomobile facade，Go → Kotlin 的 44 个 API | 复用 `mygo-music-car/android-bridge`；新增 `contentKind` / `cache` 少量 API |
| 领域层 | `ContentItem` 统一模型、内容仓库、播放意图、离线策略 | 新增；`ContentItem` 由现有 `BrowseItem` / `Track` 归一 |
| 应用层 | 车机 Compose UI、语音意图、专注模式 | 复用 MUSE 主题与播放器；新增语音与离线入口 |

## 统一内容模型

四种内容在 UI 上必须长得一样，才能用一个播放器、一个队列：

```
ContentItem {
    id, kind (MUSIC | PODCAST | AUDIOBOOK | RADIO),
    title, subtitle, cover,
    durationMs | episodeCount,
    playable: Stream | Series,
    sourceId, capabilities
}
```

映射规则：

| 现有结构 | 映射到 |
|---|---|
| `Track`（音乐单曲） | `ContentItem(kind=MUSIC, playable=Stream)` |
| `BrowseItem.SHOW`（喜马拉雅节目） | `ContentItem(kind=PODCAST, playable=Series)` |
| `BrowseItem.ALBUM` | `ContentItem(kind=AUDIOBOOK 或 MUSIC，按插件声明)` |
| `Station`（电台） | `ContentItem(kind=RADIO, playable=Stream)` |

**关键点**：`kind` 由插件声明，不由 UI 从文案猜。阶段 J 的教训是 UI 写「专辑」而
ximalaya-web 只导出 `show()`，导致详情页打不开；希音把类型判定前移到插件元数据。

## 复用清单（来自 MyGo Car 阶段 A–K）

| 能力 | 现有 API | 希音用法 |
|---|---|---|
| 插件发现 | `listPluginsJSON` | 按 `signIn` / `browse` / `lyrics` 能力位构建音源矩阵 |
| 内容浏览 | `browseJSON` / `searchJSON` | 统一搜索四类型内容 |
| 详情 | `openDetailJSON` | 候选 kind 回退 + `Browse` 兜底，直接沿用 |
| 播放 | `playJSON` / `playAt` / `queueJSON` | 连续播放章节时不重建队列 |
| 歌词/逐字稿 | `lyricsJSON` | 播客逐字稿走同一条路径 |
| 登录 | `signInJSON` / `accountJSON` | 二维码与 WebView 两条路径 |
| 视觉 | `CarColors` / `MuseCover` | MUSE 四色 + Canvas 抽象封面 |

## 新增能力

1. **语音意图层**：把 ASR 文本映射到现有 ViewModel 动作（`playTrack`、`next`、
   `setPlayerPane`、`selectSource`），不新开一套命令通道。
2. **离线缓存**：Media3 `Cache` + 按内容类型制定淘汰策略（有声书优先保留进度）。
3. **进度同步**：有声书/播客的章节进度需要跨设备同步接口（待定：本地优先 or 云）。
4. **车机适配层**：分辨率与输入方式（触摸/旋钮/方向盘）差异收敛到一个配置。

## 数据流（播放一集播客）

```
语音/触控 → ViewModel.playContent(item)
   → Repository.resolve(item) → facade.playJSON / openDetailJSON
   → Go 插件返回 Stream{URL, Headers}
   → MediaOutputImpl → ExoPlayer → 音频输出
   → "track" 事件 → 刷新逐字稿 / 章节进度
```

## 明确不做

- 不重写插件运行时；
- 不在阶段 B 之前引入云端账号体系；
- 不把 MyGo Car 源码复制进本仓库（依赖与合并策略在阶段 B 定）。
