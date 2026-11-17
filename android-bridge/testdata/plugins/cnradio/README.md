# 云听 · 央广在线收音机 — MyGo Music 插件

把 [radio.cn 电台直播页](https://www.radio.cn/pc-portal/erji/radioStation.html) 的电台目录接进 [MyGo Music](https://github.com/egoist/mygo-music)：全国 **930+ 个电台**（实测 937，动态增减）、按地区/类型浏览、名称与正在直播节目搜索、今日节目回听，直播在播放器中持续播放。

> **仓库**：https://github.com/andy8401/mygo-music-cnradio
> **一键安装**（桌面端点开会唤起 MyGo Music）：`mygo-music://install-plugin?repo=andy8401/mygo-music-cnradio`
> 仓库已带 topic `mygo-music-plugin`，可被[官方插件目录](https://mygo-music.egoist.dev/plugins)收录。

---

## 一、网页分析

### 1.1 页面性质

`radioStation.html` 是云听 PC 门户的「电台直播」页。HTML 只有骨架（地区、类型、日期、节目单容器），**所有数据由 jQuery 动态拉取**，核心在三个文件里：

| 文件 | 作用 |
| --- | --- |
| `js/api.js?062` | API 网关地址、header、MD5 签名、9 个接口的路径表、全局 `request()` |
| `erji/radioStation.html` | 页面逻辑：`getRadioList` / `getRadioProvinceList` / `getRadioCategoryList` / `getListenBackList` / `getProgramList` |
| `js/hiradio.min.js?111` | 播放器（HLS 播放），与本插件无关 |

页面默认只渲染 19 个国家台（`getRadioList()` 无参数）。地区/类型切换只是换一组筛选参数重新请求同一个接口。

### 1.2 网关与接口

单体服务网关：`https://ytmsout.radio.cn`（`api.js` 的 `msApi`；`uType=0`，所以不走 `ytapi.radio.cn/ytsrv/srv`）。

| 页面函数 | 路径 | 方法 | 参数 | 返回 `data` |
| --- | --- | --- | --- | --- |
| `getRadioProvinceList` | `/web/appProvince/list/all` | GET | — | 32 个 `{provinceName, provinceCode}`，含「国家 0」与「新疆兵团 660000」 |
| `getRadioCategoryList` | `/web/appCategory/list/all` | GET | — | 10 个 `{id, categoryName}`：全部/热门/新闻/财经/生活/交通/综艺/音乐/故事/民族 |
| `getRadioList` | `/web/appBroadcast/list` | GET | `categoryId`, `provinceCode` | 电台数组，**含直播流地址** |
| `getListenBackList` | `/web/appBroadcast/listenbackList` | GET | `provinceCode` | 19 个可回听频道 `{id, broadcastName}` |
| `getProgramList` | `/web/appProgram/listByDate` | GET | `date`, `broadcastId` | 节目数组，含 `.m4a` 回听直链 |

响应统一为 `{"code":0,"message":"SUCCESS","data":[...]}`。

### 1.3 请求签名（本插件已复现）

`api.js` 中固定密钥 `key = f0fc4c668392f9f9a447e48584c214ee`。每个 GET 带两个 header：

```
timestamp = <毫秒时间戳>
sign      = UPPER( MD5( 参数按 key 升序拼成 "k=v&k=v" + "&timestamp=" + timestamp + "&key=" + key ) )
```

固定 header：`Content-Type: application/json`、`equipmentId: 0000`、`platformCode: WEB`（另加 Referer/UA 更稳）。
无参数时签名串就是 `timestamp=<ms>&key=<固定key>`。
本插件直接用官方 host API `host.crypto.md5()`（返回小写 hex）再 `.toUpperCase()`，不需要自己实现 MD5。

> 注意：单参数请求在原页面会把值追加到路径末尾（`/web/appBroadcast/listenbackList/`）；实测以 query 形式 `?provinceCode=` 同样返回 `code:0`，本插件统一用 query。

### 1.4 电台数据字段与插件映射

| 接口字段 | 含义 | 插件用途 |
| --- | --- | --- |
| <mark>**contentId**</mark> | 电台 ID | `Station.id = live:<contentId>`，回听的 `broadcastId` 同号 |
| `title` | 电台名 | `Station.name` / `Track.name` |
| `subtitle` | 正在直播的节目名 | `Station.description`，搜索也匹配它 |
| `image` | 台标，HTTPS `ytmedia.radio.cn` | `imageUrl` |
| <mark>**playUrlMulti** / **mp3PlayUrlHigh**</mark> | 高码率 HLS `m3u8` | 直播高画质源 |
| `mp3PlayUrlLow` / `playUrlLow` | 低码率 HLS `m3u8` | 直播低码率源 |
| — | `provinceCode` 不在返回体里 | 插件在遍历省份时自行记录，供 `stream()` 重新定位 |

<mark>**所有直播流都是 HLS**</mark>：`http://ytcast2.radio.cn/110/radios/40639/index_40639.m3u8?type=1&key=<动态签名>&time=<动态时间戳>`，playlist 指向 10 秒一个的 `.ts` 分片。
`key`/`time` **每次请求都变**，所以插件从不缓存流地址，每次播放重新请求该电台所属地区的列表。
回听则是 `https://ytrecordbroadcast.radio.cn/echo/.../xxx.m4a?...`，是普通 `.m4a` 文件，实测 `Range` 请求返回 `206 / audio/x-m4a / ftyp M4A`。

### 1.5 实测数据规模（2026-11-13）

| 项目 | 结果 | 获取方式 |
| --- | --- | --- |
| 地区 | 32 | `appProvince/list/all` |
| 类型 | 10 | `appCategory/list/all` |
| 页面默认电台 | 19（国家台） | `getRadioList()` 无参 |
| <mark>**全量去重电台**</mark> | <mark>**937**</mark>（动态增减） | 遍历 32 个 `provinceCode` + 10 个 `categoryId` 后按 `contentId` 去重 |
| 可回听频道 | 19 | `listenbackList` |
| 中国之声单日节目 | 13–23（按日期变化） | `listByDate` |

### 1.6 北京地区到底有多少频道

| 核实方式 | 结果 |
| --- | --- |
| 网页「地区 → 北京」触发的 `getRadioList(0, 110000)` | **5 个**：北京城市广播副中心之声、北京交通广播、北京音乐广播、大兴人民广播电台、北京969经典调频 |
| 10 个类别 × `provinceCode=110000` 去重 | 仍是 5 个，无新增 |
| 全量 937 个电台中名称含「北京」 | 4 个（另 1 个「大兴人民广播电台」在北京分组内） |
| `place_id` / `areaCode` / `pageNum` / `pageSize` / `limit` 等隐藏参数 | 均不改变结果（未知参数被忽略，回退成 19 个国家台） |
| 无头 Edge 渲染页面 | 地区列表 32 项，确认北京 code = `110000` |

**结论**：云听网页版本身对北京只收录 5 个本地直播频道，不是插件漏采。v1.1.0 起「按地区 → 北京」额外分组列出 19 个全国台（各地均可收听），页面合计 24 个。

---

## 二、为什么需要「转流桥」

MyGo Music 是纯 Go 解码，插件的 `stream()` 只能返回 `{url, type}`，`type` 仅支持 `mp3 / flac / ogg / wav / m4a / aac`（见官方 `internal/music/music.go:249` 与 README）。其中 `aac` 指 **ADTS 帧流**：`internal/player/adts.go` 的 `adtsHeader()` 要求流的第 0 字节就是 `FF F1/F9`，否则直接报 `aac: not an ADTS stream`。

云听直播给的是 `m3u8` → `.ts` 分片（分片首字节 `47 40 ...`，MPEG-TS 包），因此**不能把 m3u8 直接交给播放器**。

插件的解决办法：`bridge.py`（Python 3 标准库，无第三方依赖）拉 HLS playlist、剥掉 188 字节 TS 包头、抽出完整 ADTS AAC 帧，在 `127.0.0.1:<port>/live.aac` 上输出一条**永不结束**的 ADTS 流；`stream()` 返回这个本地地址，播放器边下边播。也支持用 ffmpeg 完成同样的转封装（可选）。

```
电台卡片 → stationNext() → stream()
   ├─ 直播：签名 API → 最新 m3u8 → bridge.py / ffmpeg → http://127.0.0.1:PORT/live.aac (type: aac)
   └─ 回听：listByDate → https://...m4a → 直连播放 (type: m4a, 无需桥)
```

---

## 三、文件清单

| 文件 | 作用 |
| --- | --- |
| `plugin.json` | 清单：权限、设置、侧栏导航、请求头 |
| `index.js` | 全部插件能力：签名请求、目录缓存、home/browse/search/stations/stationNext/stream |
| `bridge.py` | HLS→ADTS 本地转流桥（Python 3 标准库） |
| `locales/zh-CN.json` | 中文翻译 |

导出的函数（未导出的能力 MyGo Music 会自动隐藏对应界面）：

| 函数 | 返回 | 说明 |
| --- | --- | --- |
| `stations()` | `[Station]` | 930+ 个电台（实测 937，动态增减） |
| `home()` | `Browse` | 国家台、热门、地区/类型入口、回听入口 |
| `browse({page})` | `Browse` | `regions` / `categories` / `refresh` / `region:<code>` / `cat:<id>` / `replay` / `playback:<id>` / `all`；地区页额外分组列出 19 个全国台 |
| `search({query,kind,offset})` | `{tracks, more}` | 匹配台名、正在直播节目、地区 |
| `suggest({query})` | `[string]` | 台名前缀建议 |
| `stationNext({id})` | `[Track]` | 每次生成唯一 track id，流断了自动续播 |
| `stream({id,quality})` | `Stream` | 直播走桥，回听走 m4a |
| `setSetting({key,value})` | — | 设置变化时重启桥 |

---

## 四、安装

1. **准备运行时**：安装 Python 3（命令为 `python3` / `python` / `py` 之一）**或** ffmpeg，且可在 PATH 中调用。macOS/Linux 通常自带 Python 3；Windows 装官方 Python 即可。
2. **导入插件**（任选其一）：
   - **GitHub（推荐）**：设置 → 插件 → + → 从 GitHub 安装，填 `andy8401/mygo-music-cnradio`；或点 [一键安装](mygo-music://install-plugin?repo=andy8401/mygo-music-cnradio)。
   - **本地**：选择本文件夹，或用 `mygo-music-cnradio.zip`（zip 根目录即 `plugin.json`）导入。
3. **授权**：首次安装会列出权限，需要允许
   - `network`：`*.radio.cn`、`127.0.0.1`、`localhost`
   - `process`：`python3` / `python` / `py` / `ffmpeg`（用于启动本地转流桥）
4. 侧栏出现「云听首页 / 全部电台 / 按地区 / 按类型 / 节目回听」，点电台即播。「按地区」「按类型」页顶部有「刷新频道目录」，可强制重建目录并显示拉取失败的地区码。

> `process` 权限只接受**命令名**。若你的 Python 命令不在上述三个名字里，请把可执行程序以这三个名字之一放到 PATH（不要填绝对路径，绝对路径不在已声明的权限内）。

### 设置项

| key | 默认 | 说明 |
| --- | --- | --- |
| `bridge` | `auto` | `auto`（先 Python 桥再 ffmpeg）/ `python` / `ffmpeg` / `direct`（把 m3u8 直接给播放器，仅调试，会解码失败） |
| `pythonCmd` | 空 | 填 `python3` / `python` / `py`，留空依次尝试 |
| `ffmpegCmd` | 空 | 一般填 `ffmpeg` |
| `quality` | `high` | `high` 用 playUrlMulti / mp3PlayUrlHigh，`low` 用低码率源 |

---

## 五、验证记录（本机实测，2026-11-13）

| # | 检查 | 结果 |
| --- | --- | --- |
| 1 | 三个目录接口带签名 GET | 全部 `HTTP 200 / code:0` |
| 2 | 遍历 32 地区 + 10 类型 | 首轮去重后 **920** 个电台；后续复测 **937**（目录动态增减，非固定值） |
| 3 | 回听 `m4a` 直链 | `HTTP 206`，`audio/x-m4a`，文件头 `ftypM4A` |
| 4 | HLS playlist | `HTTP 200`，`#EXT-X-TARGETDURATION:10`，5 个 `.ts` 分片 |
| 5 | 分片首字节 | `47 40 11 1d ...`（MPEG-TS，非 ADTS）→ 证实必须转封装 |
| 6 | `verify_bridge.py` | `/health = cnradio-bridge-ok`；`/live.aac` 6 秒收 122880 字节，解析出 **216 个完整 ADTS 帧**，44100 Hz，`fff15080…` |
| 7 | `node --check index.js` / `py_compile bridge.py` / 两个 JSON | 全部通过 |
| 8 | `harness.mjs` v1.0.0（模拟 MyGo host） | 13 项 ALL PASS，`live.aac` 输出 `fff15080459ffc21` |
| 9 | `harness.mjs` v1.1.0（18 项，含限流回归） | **ALL PASS**：937 电台、北京页 5+19、刷新页 33 入口、瞬时 503 重试后仍 5 个、持续 503 沿用完整目录仍 5 个 |
| 10 | 高频请求观察 | `listenbackList` 返回 `503 Service Temporarily Unavailable`，证实云端存在限流 |

### 5.1 地区完整性与限流修复（v1.1.0）

- **根因**：v1.0.0 首次加载用 `Promise.all` 同时发 32 个省 + 10 个类别请求；云端限流时某个地区返回 503，被 `catch` 吞成空数组，而这个「缺一截」的目录还会被缓存 30 分钟。表现就是某个地区频道数不对。
- **修复**：每批 3 个请求、批间 180ms；失败按 300ms / 1200ms 退避重试 2 次；失败地区从上次完整目录回填；不完整目录只缓存 60 秒且不覆盖完整缓存；刷新页列出失败地区码。
- **手动刷新**：侧栏「按地区 → 刷新频道目录」，或打开 `page: refresh`。

---

## 六、限制与注意

- **直播依赖 Python 3 或 ffmpeg**：回听（m4a）不需要任何外部程序，开箱即播。没装运行时时点直播会提示 `setup`，请在设置里配置。
- 直播延迟约 10–20 秒（HLS 分片 + 桥的节流），属正常现象。
- 流地址的 `key/time` 有时效，插件每次播放都重新签名取新地址；切台会重启桥，同一电台复用桥。
- `direct` 模式仅用于确认 HLS 与播放器的不兼容，正常收听不要用。
- 插件只请求公开的目录/流接口，**完整目录缓存 30 分钟，因限流而残缺的目录只缓存 60 秒**且在下次成功拉取前不覆盖完整缓存；请仅作个人学习与自用，遵守站点服务条款，不要高频轮询或分发流地址。

---

## 七、发布与更新

- 仓库：`https://github.com/andy8401/mygo-music-cnradio`，`plugin.json` 位于仓库根目录，MyGo Music 可直接安装。
- 没有 Release 时，客户端取默认分支最新一次提交；打 tag 或推送新提交即视为新版本（新增权限会再次询问用户）。
- 仓库已设置 topic `mygo-music-plugin`（另有 `mygo-music` / `radio` / `internet-radio` / `cnradio`），可被[官方插件目录](https://mygo-music.egoist.dev/plugins)收录。
- 插件版本号写在 `plugin.json` 的 `version`，当前 **`1.1.0`**。

### 7.1 版本记录

| 版本 | 内容 |
| --- | --- |
| `1.0.0` | 首次发布：签名接口复现、930+ 电台目录、按地区/类型浏览、搜索、节目回听、HLS→ADTS 本地转流桥。 |
| `1.1.0` | 修复云端限流导致的**地区频道缺失**：分批请求 + 重试退避、失败地区从上次完整目录回填、残缺目录短 TTL；新增「刷新频道目录」页；地区页额外分组列出 19 个全国台（如北京页由 5 个变 5+19=24 个）。 |
