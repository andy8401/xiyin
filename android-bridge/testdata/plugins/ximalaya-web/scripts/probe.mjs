// Read-only endpoint self-check. No login, no account data, no writes.
// Prints a table of HTTP status / business ret / key fields for every endpoint
// the plugin calls on www.ximalaya.com.
const SITE = "https://www.ximalaya.com";
const SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
const CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
const HEADERS = { "User-Agent": SAFARI, Referer: SITE + "/", Accept: "application/json" };

async function call(path, userAgent = SAFARI) {
  const response = await fetch(SITE + path, {
    headers: { ...HEADERS, "User-Agent": userAgent },
    signal: AbortSignal.timeout(12000),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: response.status, ret: json?.ret ?? "-", data: json?.data ?? json?.trackInfo ?? null, body: json };
}

const checks = [
  ["搜索·声音", "/revision/search/seo?kw=%E4%B8%89%E4%BD%93&core=track&page=1&rows=3", "track"],
  ["搜索·专辑", "/revision/search/seo?kw=%E4%B8%89%E4%BD%93&core=album&page=1&rows=3", "album"],
  ["专辑详情", "/revision/album?albumId=56974128", "mainInfo"],
  ["单集详情", "/revision/track/simple?trackId=500761257", "trackInfo"],
  ["专辑节目分页", "/revision/play/v1/show?id=56974128&ptype=0&num=1&size=3&sort=0", "tracksAudioPlay"],
  ["首页推荐", "/revision/explore/guessYouLike?page=1&pageSize=5", "recommendInfoList"],
  ["榜单", "/revision/rank/getRankList", "freeAlbumRank"],
  ["分类列表", "/revision/category/v2/allCategory", "categories"],
  ["分类专辑", "/revision/category/v2/albums?pageNum=1&pageSize=3&sort=1&categoryId=2", "albums"],
  ["热搜词", "/revision/search/hotWord", "hots"],
  ["账号状态(未登录)", "/revision/main/getCurrentUser", null],
];

const rows = [];
for (const [label, path, field] of checks) {
  try {
    const result = await call(path);
    const hasField = field ? Boolean(result.data?.[field] || (field === "tracksAudioPlay" && result.data?.tracksAudioPlay)) : true;
    rows.push([label, String(result.status), String(result.ret), field ? (hasField ? "OK" : "MISSING") : "n/a"]);
  } catch (error) {
    rows.push([label, "ERR", error.name, "FAIL"]);
  }
}
// Authenticated endpoints are expected to ask for login here.
for (const [label, path] of [
  ["订阅(未登录)", "/revision/my/getSubscriptionSynthesize?pageNum=1&pageSize=30"],
  ["已购(未登录)", "/revision/my/getHasBroughtAlbums?pageNum=1&pageSize=30"],
  ["云端历史(未登录)", "/revision/track/history/listen?includeChannel=false&includeRadio=false&pageNum=1&pageSize=30"],
]) {
  try {
    const result = await call(path);
    rows.push([label, String(result.status), String(result.ret), "需登录"]);
  } catch (error) { rows.push([label, "ERR", error.name, "FAIL"]); }
}
// Web player: Safari UA works, Chrome UA is rejected by risk control.
const streamPath = "/mobile-playpage/track/v3/baseInfo/" + Date.now() + "?device=web&trackId=351009997&trackQualityLevel=1";
for (const [label, ua] of [["取流(Safari UA)", SAFARI], ["取流(Chrome UA)", CHROME]]) {
  try {
    const result = await call(streamPath, ua);
    const count = result.body?.trackInfo?.playUrlList?.length ?? 0;
    rows.push([label, String(result.status), String(result.ret), count ? `playUrlList=${count}` : "被风控/无地址"]);
  } catch (error) { rows.push([label, "ERR", error.name, "FAIL"]); }
}

const widths = [22, 8, 8, 24];
const line = (cells) => cells.map((cell, i) => String(cell).padEnd(widths[i])).join(" | ");
console.log(line(["端点", "HTTP", "ret", "结果"]));
console.log(widths.map((w) => "-".repeat(w)).join("-+-"));
for (const row of rows) console.log(line(row));
console.log(`\n${rows.length} endpoints checked. Authenticated/VIP playback requires an in-app test.`);
