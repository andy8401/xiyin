# mygo-ximalaya-web-plugin

面向 [MyGo Music](https://github.com/egoist/mygo-music) 的 **喜马拉雅网页版插件**，直接调用 `www.ximalaya.com` 的网页接口，把官网功能搬进播放器。需要 **MyGo Music 0.1.20 或更新版本**。

当前版本 **`0.1.1`**（修复全局音质设为「无损」时音频无法播放）。

本工程按 `MyGo Music/plugins/ximalaya` 的插件设计（清单格式、回调契约、模块划分、单文件打包）独立实现，**未复制其源码**，也没有运行时或构建依赖（AES-128-ECB 与打包器均为本工程自实现）。

## 功能

| 功能 | 说明 | 需要登录 |
| --- | --- | --- |
| 搜索声音 / 节目专辑 | 「歌曲」标签搜声音、「播客」标签搜专辑；支持粘贴官网专辑/单集链接，以及 `album:ID` / `sound:ID` | 否 |
| 专辑与节目分页 | 打开专辑、按正序 30 集一页翻页 | 否 |
| 播放取流 | 官方网页播放器接口取音频地址，解密、校验、按音质选格式，交给宿主播放器 | 否（免费内容） |
| 首页 | 推荐、榜单精选、分类入口、热搜词，登录后追加订阅/已购/云端历史预览 | 否 |
| 发现 | 在线推荐 + 可点击的热门搜索词（直接进入搜索结果） | 否 |
| 榜单 | 官网「免费经典榜」「付费畅销榜」，专辑可直接打开 | 否 |
| 分类 | 官网一级分类列表，逐类分页浏览专辑 | 否 |
| 我的订阅 | 分页读取账号订阅，只读，不修改喜马拉雅订阅 | 是 |
| 已购专辑 | 分页读取单独购买的专辑 | 是 |
| 最近收听 | 网页版云端历史，按今天/昨天/更早分组，显示上次听到的时间 | 是 |
| 本机记录 | 此设备最近 50 集、浏览过的最近 20 个专辑 | 否 |
| 扫码登录 | 打开 `passport.ximalaya.com` 官方登录窗口，手机 App 扫码确认，会话保存在本机 | — |

## 安装

1. MyGo Music → 设置 → 插件 → `+` → **从本地文件夹安装**。
2. 选择包含 `plugin.json` 的 `ximalaya-web-plugin` 目录（或解压 `dist/ximalaya-web-v0.1.1.zip` 后选择其根目录）。
3. 在来源选择器切换到「喜马拉雅网页版」，点击登录，在弹出的官方网页中扫码。
4. 不登录也能使用搜索、专辑、榜单、分类、发现和免费内容播放。

`index.js` 是已构建的单文件产物，安装插件**不需要 Node、Python 或任何 npm 依赖**。

## 目录

```text
plugin.json          MyGo 插件清单（id=ximalaya-web，playback=stream）
index.js             构建生成的单文件 ESM 插件入口
src/utils.js         常量、ID/分页校验、链接解析、图片 CDN 白名单
src/session.js       Cookie 解析、会话持久化、登录态与并发代号
src/client.js        统一请求网关：超时、业务码、风控、Cookie 归属
src/auth.js          官方网页扫码登录、账号验证
src/models.js        网页数据 → MyGo 数据模型
src/catalog.js       搜索、专辑、订阅、已购、历史、榜单、分类、热搜、本机记录
src/aes.js           纯 JS AES-128-ECB 解密（无第三方依赖）
src/playback.js      音频地址解密、地址校验、格式与音质选择
src/main.js          MyGo 回调入口：home / browse / recent
scripts/build.mjs    零依赖打包器（src → index.js）
scripts/probe.mjs    公开接口自检（含 UA 对照）
scripts/smoke.mjs    公开接口端到端冒烟（音频只读 32 字节）
scripts/pack.py      生成可安装 ZIP
tests/               Node + mock 宿主单元测试
```

## 网页接口（2026-11-13 实测）

| 端点 | 用途 | 实测 |
| --- | --- | --- |
| `/revision/search/seo` | 声音 / 专辑搜索 | HTTP 200，ret 200 |
| `/revision/album` | 专辑详情 | HTTP 200，ret 200 |
| `/revision/track/simple` | 单集详情 | HTTP 200，ret 200 |
| `/revision/play/v1/show` | 专辑节目分页 | HTTP 200，ret 200 |
| `/revision/explore/guessYouLike` | 首页推荐 | HTTP 200，ret 200 |
| `/revision/rank/getRankList` | 榜单 | HTTP 200，ret 200 |
| `/revision/category/v2/allCategory` | 分类列表 | HTTP 200，ret 200 |
| `/revision/category/v2/albums` | 分类专辑分页 | HTTP 200，ret 200 |
| `/revision/search/hotWord` | 热搜词 | HTTP 200，ret 200 |
| `/revision/main/getCurrentUser` | 账号状态 | 未登录 ret 401 |
| `/revision/my/getSubscriptionSynthesize` | 订阅 | 未登录 ret 401 |
| `/revision/my/getHasBroughtAlbums` | 已购 | 未登录 ret 401 |
| `/revision/track/history/listen` | 云端历史 | 未登录 ret 401 |
| `/mobile-playpage/track/v3/baseInfo/<ts>` | 播放取流 | Safari UA：ret 0，5 档音频地址 |

**取流对 User-Agent 敏感**：同一单集使用 Chrome UA 返回 `ret:1001`（风控），使用 Safari UA 返回 `ret:0`。插件固定使用 Safari UA，改动该项会直接导致播放失败。

音频地址是 AES-128-ECB + PKCS#7 加密的 base64url，公开播放器密钥 `aaad3e4fd540b0f79dca95606e72bf93`（来自官网播放器，不是账号凭证）。实测解密得到 `a.xmcdn.com` 地址，Range 读取返回 `HTTP 206 / bytes 0-31/9255895`。

## 验证

```sh
node scripts/build.mjs       # 构建 index.js（输出字节数与 sha256）
node --check index.js        # 语法校验
node --test tests/*.test.mjs # 单元测试：17/17 通过
node scripts/probe.mjs       # 16 个端点自检（公开 + 登录态 + UA 对照）
node scripts/smoke.mjs       # 真实只读冒烟
python scripts/pack.py       # 生成 dist/ximalaya-web-v0.1.1.zip
```

`npm run build / check / test / smoke / probe / pack` 是等价入口，**无需 `npm install`**。

实测冒烟输出（`node scripts/smoke.mjs`）：

```text
home: 12 recommended albums; 4 sections
search shows: 30 results; more=true
search tracks: 30 results; more=true
rank: 免费经典榜=10, 付费畅销榜=10
categories: 19 links; category:2 -> 30 albums
show: 阴阳委托人｜灵异悬疑多人有声剧; pages=30+30
stream: type=mp3; HTTP 206; bytes 0-31/9255895
lossless fallback: quality=high; type=mp3; fallbackFrom=lossless
local-recent: 1 records
```

## 边界与安全

- **只读**：不订阅/取消订阅、不点赞、不评论、不购买、不回写云端播放进度、不自动断点续播、不批量下载。
- 会话 Cookie 只发送给 `www.ximalaya.com`，**不附加给音频 CDN**；只接受 `ximalaya.com` 域且 `path=/` 的回写 Cookie。
- 图片、音频地址都做 `.xmcdn.com` 域名白名单；用户粘贴的链接只解析出数字 ID，不会被当作任意 URL 请求（防 SSRF）。
- 会话保存在宿主的本地 JSON 存储，不是系统钥匙串；请勿上传会话、日志中的凭证或 Cookie。退出登录只清除本插件会话，本机记录保留。
- 取流会检查授权状态并拒绝已知试听地址；付费/VIP 内容能否播放以服务端实际响应为准，未在真实账号上验收。
- 同音质档位优先选择 MP3：部分 M4A 实际是 HE-AACv2（SBR/PS），超出宿主 AAC-LC 解码器支持范围。
- **0.1.1 修复**：全局音质设为「无损」时，若服务端没有 FLAC（喜马拉雅网页接口通常只有 `M4A_128 / M4A_64 / MP3_64 / M4A_24 / MP3_32 / AAC_24`），自动回退到高音质，返回 `quality: "high"` 并附 `fallbackFrom: "lossless"`；不会冒充无损，也不会让播放直接失败。有真实 FLAC 时仍按 lossless 返回。
- 「人气主播榜」是主播资料而非专辑，未渲染成可播放列表；其余两个专辑榜可用。
- 网页验证、风控或接口变更可能导致功能暂不可用；出现验证提示时重新登录并在官方网页确认访问正常。

## 更新记录

- `0.1.1`：修复全局音质为 lossless 时音频无法播放（无 FLAC 自动回退高音质，并如实标注实际档位）；补充该路径的单元测试与真实接口冒烟。
- `0.1.0`：首版，覆盖搜索、专辑、榜单、分类、热搜、订阅、已购、云端历史与播放取流。

## 许可

本工程 MIT。设计参考的已安装插件 `mygo-ximalaya` 为 AGPL-3.0-only，本工程未使用其源码或依赖。
