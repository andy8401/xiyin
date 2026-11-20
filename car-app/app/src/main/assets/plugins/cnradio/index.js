// MyGo Music plugin: 云听 (radio.cn) + 喜马拉雅 (ximalaya.com) 在线收音机
//
// Primary source: 云听 radio.cn. Its data comes from https://ytmsout.radio.cn
// (declared in /pc-portal/js/api.js). Every GET carries timestamp + sign =
// UPPER(MD5(sorted_query + "&timestamp=<ms>&key=<fixed>")). host.crypto.md5()
// is lowercase hex, hence .toUpperCase().
//
// Secondary source: 喜马拉雅 https://www.ximalaya.com/radio . Its data comes
// from https://m.ximalaya.com/radio-first-page-app/{homePage,search} with no
// signature. Only live stations are merged; replay stays 云听-only. Stations
// with the same normalised name use the 云听 entry (primary wins).
//
// Audio:
//   * 云听 live   -> HLS of MPEG-TS segments -> bridge.py strips TS headers.
//   * 喜马拉雅 live -> HLS of raw ADTS .aac segments -> bridge.py --mode aac.
//   * replay      -> https://...m4a files, which the player opens directly.

const t = host.t;
const BASE = "https://ytmsout.radio.cn";
const SIGN_KEY = "f0fc4c668392f9f9a447e48584c214ee";
const REFERER = "https://www.radio.cn/";
const API = {
  provinces: "/web/appProvince/list/all",
  categories: "/web/appCategory/list/all",
  stations: "/web/appBroadcast/list",
  listenback: "/web/appBroadcast/listenbackList",
  programs: "/web/appProgram/listByDate",
};

// ---- 喜马拉雅 secondary source -------------------------------------------
const XM_BASE = "https://m.ximalaya.com";
const XM_LIVE_BASE = "https://live.ximalaya.com";
const XM_REFERER = "https://m.ximalaya.com/radio/";
const XM_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
  "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
const XM_PAGE_SIZE = 30;
const XM_MAX_PAGES = 12; // safety cap; a category never has more than 360 stations
// 喜马拉雅 categoryId -> 云听 category id. The user wants 云听's category
// list kept as-is, plus one new "其他" bucket for the leftovers.
const XM_CAT_MAP = {
  "5": "9",    // 新闻台 -> 新闻
  "14": "5",   // 音乐台 -> 音乐
  "1": "15",   // 交通台 -> 交通
  "11": "11",  // 经济台 -> 财经
  "10": "13",  // 生活台 -> 生活
  "4": "17",   // 文艺台 -> 综艺
  "8": "17",   // 曲艺台 -> 综艺
  "13": "17",  // 都市台 -> 综艺
  // 旅游台(7)/方言台(6)/外语台(3)/综合台(12)/其他台(15)/体育台(2) -> 其他
};
const OTHER_CAT = "other";
const CATALOG_TTL = 30 * 60 * 1000; // station names/covers; never cache stream URLs
const PAGE_SIZE = 60;

function fail(code, message) {
  const e = new Error(message || code);
  e.code = code;
  return e;
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------- signed API

async function api(path, params) {
  const data = params || {};
  const keys = Object.keys(data).sort();
  const query = keys.map((k) => k + "=" + data[k]).join("&");
  const stamp = Date.now();
  const text = (query ? query + "&" : "") + "timestamp=" + stamp + "&key=" + SIGN_KEY;
  const sign = String(host.crypto.md5(text)).toUpperCase();
  const url = new URL(BASE + path);
  keys.forEach((k) => url.searchParams.set(k, String(data[k])));
  const res = await fetch(url.toString(), {
    headers: {
      "Content-Type": "application/json",
      "equipmentId": "0000",
      "platformCode": "WEB",
      "timestamp": String(stamp),
      "sign": sign,
      "Referer": REFERER,
      "User-Agent": "Mozilla/5.0 (compatible; MyGoMusic/1.0)",
    },
  });
  if (!res.ok) throw new Error("云听接口 HTTP " + res.status);
  const body = await res.json();
  if (!body || body.code !== 0) {
    throw new Error("云听接口 " + ((body && body.message) || "返回异常"));
  }
  return body;
}

// -------------------------------------------------------- 喜马拉雅 (secondary)
// No signature; iPhone UA + Referer is enough (verified against the live site).
async function xmApi(path, params) {
  const data = params || {};
  const url = new URL(/^https?:\/\//.test(path) ? path : XM_BASE + path);
  Object.keys(data).forEach((k) => url.searchParams.set(k, String(data[k])));
  const res = await fetch(url.toString(), {
    headers: { "User-Agent": XM_UA, "Referer": XM_REFERER, "Accept": "application/json" },
  });
  if (!res.ok) throw new Error("喜马拉雅接口 HTTP " + res.status);
  const body = await res.json();
  if (!body || body.ret !== 0) {
    throw new Error("喜马拉雅接口 " + ((body && body.msg) || "返回异常"));
  }
  return body.data;
}

// Normalise a station name so the same station on both platforms collides.
// Strip common broadcast suffixes, punctuation and whitespace.
function normName(name) {
  let s = String(name || "");
  try { s = s.normalize("NFKC"); } catch (err) { /* old engine */ }
  s = s.toLowerCase();
  s = s.replace(/(人民广播电台|广播电台|交通广播电台|电视台|广播|电台|频率|频道|之声)/g, "");
  s = s.replace(/[\s\-—_·（）()【】\[\].,，。!！?？:：'"“”]/g, "");
  return s;
}

// -------------------------------------------------------------------- catalog
// The web page only shows 19 national stations by default. Walking every
// province code yields the full directory (930+ unique stations at last check).
//
// The API rate-limits bursts: firing 32 provinces + 10 categories at once made
// it answer 503 for part of them. A failed province used to be cached as an
// empty list for 30 minutes, which is exactly how a region "loses" channels.
// Now: small batches, retries with backoff, fall back to the last complete
// directory for a failed region, and never cache a partial build for long.

const CATALOG_BATCH = 3;
const CATALOG_GAP_MS = 180;
const RETRY_TIMES = 2;
const PARTIAL_TTL = 60 * 1000;

async function apiRetry(path, params) {
  let last;
  for (let i = 0; i <= RETRY_TIMES; i++) {
    try {
      return await api(path, params);
    } catch (err) {
      last = err;
      if (i < RETRY_TIMES) await sleep(300 * (i + 1) * (i + 1)); // 300ms, 1200ms
    }
  }
  throw last;
}

async function mapBatched(items, worker) {
  const out = [];
  for (let i = 0; i < items.length; i += CATALOG_BATCH) {
    const batch = items.slice(i, i + CATALOG_BATCH);
    const part = await Promise.all(batch.map(worker));
    for (let j = 0; j < part.length; j++) out.push(part[j]);
    if (i + CATALOG_BATCH < items.length) await sleep(CATALOG_GAP_MS);
  }
  return out;
}

async function xmRetry(path, params) {
  let last;
  for (let i = 0; i <= RETRY_TIMES; i++) {
    try {
      return await xmApi(path, params);
    } catch (err) {
      last = err;
      if (i < RETRY_TIMES) await sleep(300 * (i + 1));
    }
  }
  throw last;
}

// Fetch all 喜马拉雅 live stations and merge into the 云听 catalog structures.
// 云听 wins whenever the normalised names collide; xm ids are prefixed "xm:".
// The function mutates byId / catIds / regionIds and returns merge metadata.
async function mergeXimalaya(previous, byId, catIds, regionIds) {
  const meta = { count: 0, skipped: 0, failures: [] };

  // Name index of everything 云听 already has (primary source wins).
  const seen = {};
  Object.keys(byId).forEach((id) => {
    const n = normName(byId[id].name);
    if (n.length >= 2) seen[n] = true;
  });

  const home = await xmRetry("/radio-first-page-app/homePage", {});
  const searchModule = (home.modules || []).filter((m) => m.type === "SEARCH")[0] || {};
  const cats = (searchModule.categories || []).filter((c) => String(c.id) !== "0");
  const locs = searchModule.locations || [];
  const catName = {};
  cats.forEach((c) => { catName[String(c.id)] = c.name; });

  // 1) Full station list by category (each station carries its categoryId).
  const xmById = {};
  const catResults = await mapBatched(cats, async (c) => {
    const list = [];
    for (let page = 1; page <= XM_MAX_PAGES; page++) {
      const d = await xmRetry("/radio-first-page-app/search", {
        locationTypeId: 0, pageNum: page, pageSize: XM_PAGE_SIZE,
        categoryId: c.id, locationId: 0,
      });
      const radios = d.radios || [];
      radios.forEach((r) => list.push(r));
      if (page * (d.pageSize || XM_PAGE_SIZE) >= (d.total || 0)) break;
    }
    return { id: String(c.id), list: list };
  });
  catResults.forEach((r) => {
    r.list.forEach((x) => { xmById[String(x.id)] = x; });
  });
  if (!Object.keys(xmById).length) throw new Error("喜马拉雅目录为空");

  // 2) Region ownership (search-by-region returns station ids per province).
  const radioRegion = {};
  const provinces = locs.filter((l) => l.type === 0 && String(l.id) !== "0");
  const locResults = await mapBatched(provinces, async (l) => {
    const d = await xmRetry("/radio-first-page-app/search", {
      locationTypeId: 0, pageNum: 1, pageSize: XM_PAGE_SIZE,
      categoryId: 0, locationId: l.id,
    });
    return { code: String(l.id), list: d.radios || [] };
  });
  locResults.forEach((r) => {
    r.list.forEach((x) => {
      const key = String(x.id);
      if (!radioRegion[key]) radioRegion[key] = r.code;
    });
  });

  // 3) Merge, skipping anything 云听 already has under the same name.
  Object.keys(xmById).forEach((key) => {
    const x = xmById[key];
    const name = String(x.name || "");
    const n = normName(name);
    if (!name) return;
    if (n.length >= 2 && seen[n]) {
      meta.skipped++;
      return; // 云听 wins
    }
    if (n.length >= 2) seen[n] = true; // avoid duplicates within 喜马拉雅 too

    const id = "xm:" + key;
    const regionCode = radioRegion[key];
    const prov = regionCode
      ? (provinces.filter((p) => String(p.id) === regionCode)[0] || {})
      : {};
    byId[id] = {
      id: id,
      name: name,
      subtitle: x.programName ? ("正在直播：" + x.programName) : "",
      image: String(x.coverLarge || x.coverSmall || "").replace(/^http:/, "https:"),
      provinceCode: regionCode || "0",
      region: prov.name || "网络",
      source: "xm",
      xmId: key,
      xmCategoryId: String(x.categoryId || ""),
      xmCategoryName: x.categoryName || catName[String(x.categoryId)] || "其他台",
    };
    const cat = XM_CAT_MAP[String(x.categoryId)] || OTHER_CAT;
    if (!catIds[cat]) catIds[cat] = [];
    if (catIds[cat].indexOf(id) < 0) catIds[cat].push(id);
    const regionKey = regionCode || "0";
    if (!regionIds[regionKey]) regionIds[regionKey] = [];
    if (regionIds[regionKey].indexOf(id) < 0) regionIds[regionKey].push(id);
    meta.count++;
  });

  return meta;
}

async function buildCatalog(previous) {
  const [provRes, catRes] = await Promise.all([
    apiRetry(API.provinces),
    apiRetry(API.categories),
  ]);
  const provinces = provRes.data || [];
  const categories = catRes.data || [];
  // Keep 云听's categories untouched, then append exactly one new bucket.
  if (!categories.filter((x) => String(x.id) === OTHER_CAT).length) {
    categories.push({ id: OTHER_CAT, categoryName: t("Other") });
  }
  const byId = {};
  const regionIds = { "0": [] };
  const catIds = {};
  const failures = [];

  const regions = await mapBatched(provinces, async (p) => {
    const code = String(p.provinceCode);
    try {
      const res = await apiRetry(API.stations, { categoryId: "0", provinceCode: code });
      return { code: code, name: p.provinceName, list: res.data || [], ok: true };
    } catch (err) {
      failures.push(code);
      console.log("cnradio: province " + code + " failed: " + err.message);
      return { code: code, name: p.provinceName, list: [], ok: false };
    }
  });
  regions.forEach((r) => {
    regionIds[r.code] = [];
    r.list.forEach((item) => {
      const id = String(item.contentId);
      if (!byId[id]) {
        byId[id] = {
          id: id,
          name: item.title,
          subtitle: item.subtitle || "",
          image: item.image || "",
          provinceCode: r.code,
          region: r.name,
        };
      }
      if (regionIds[r.code].indexOf(id) < 0) regionIds[r.code].push(id);
    });
  });

  // A region that failed this round is filled from the last good catalogue,
  // so a transient 503 never silently empties a whole province.
  if (previous && previous.byId && previous.regionIds) {
    failures.forEach((code) => {
      const ids = previous.regionIds[code] || [];
      if (!regionIds[code]) regionIds[code] = [];
      ids.forEach((id) => {
        if (!byId[id] && previous.byId[id]) byId[id] = previous.byId[id];
        if (regionIds[code].indexOf(id) < 0) regionIds[code].push(id);
      });
    });
  }

  const cats = await mapBatched(categories, async (c) => {
    const id = String(c.id);
    try {
      const res = await apiRetry(API.stations, { categoryId: id, provinceCode: "0" });
      return { id: id, name: c.categoryName, list: res.data || [], ok: true };
    } catch (err) {
      failures.push("cat:" + id);
      console.log("cnradio: category " + id + " failed: " + err.message);
      return { id: id, name: c.categoryName, list: [], ok: false };
    }
  });
  cats.forEach((c) => {
    catIds[c.id] = [];
    c.list.forEach((item) => {
      const id = String(item.contentId);
      if (catIds[c.id].indexOf(id) < 0) catIds[c.id].push(id);
      // A station found only through a category filter still needs its region
      // so stream() knows which province list to re-query for a fresh URL.
      if (!byId[id]) {
        byId[id] = {
          id: id,
          name: item.title,
          subtitle: item.subtitle || "",
          image: item.image || "",
          provinceCode: "0",
          region: "国家",
        };
        regionIds["0"].push(id);
      }
    });
  });

  // Merge 喜马拉雅. It is a secondary source: never let its failure make the
  // whole catalog look partial or wipe the 云听 directory.
  let xmMeta = null;
  try {
    xmMeta = await mergeXimalaya(previous, byId, catIds, regionIds);
  } catch (err) {
    console.log("cnradio: 喜马拉雅 merge failed: " + err.message);
    failures.push("ximalaya");
    // Restore the previous 喜马拉雅 stations so a transient failure does not
    // silently drop them from an otherwise complete catalog.
    if (previous && previous.byId) {
      Object.keys(previous.byId).forEach((id) => {
        const s = previous.byId[id];
        if (!s || s.source !== "xm" || byId[id]) return;
        byId[id] = s;
        const cat = XM_CAT_MAP[s.xmCategoryId] || OTHER_CAT;
        if (!catIds[cat]) catIds[cat] = [];
        if (catIds[cat].indexOf(id) < 0) catIds[cat].push(id);
        const regionKey = s.provinceCode || "0";
        if (!regionIds[regionKey]) regionIds[regionKey] = [];
        if (regionIds[regionKey].indexOf(id) < 0) regionIds[regionKey].push(id);
      });
    }
  }
  if (xmMeta) failures.push.apply(failures, xmMeta.failures);

  return {
    at: Date.now(),
    provinces: provinces,
    categories: categories,
    stations: Object.keys(byId).map((k) => byId[k]),
    byId: byId,
    regionIds: regionIds,
    catIds: catIds,
    xmCount: xmMeta ? xmMeta.count : Object.keys(byId).filter((k) => byId[k].source === "xm").length,
    xmSkipped: xmMeta ? xmMeta.skipped : 0,
    partial: failures.length > 0,
    failures: failures,
  };
}

// ------------------------------------------------------- cache + background sync
// The app reuses one plugin VM across calls (mygo-music internal/plugin/plugin.go:
// p.running() returns p.vm once started), and its event loop stays alive between
// calls, so a module-level setTimeout survives after home()/stations() return.
// That makes a delayed, silent directory rebuild possible without blocking UI.
const BG_REFRESH_DELAY = 20 * 1000;      // complete cache: refresh 20s after first use
const BG_REFRESH_DELAY_STALE = 5 * 1000; // partial cache: repair sooner
let bgTimer = null;
let refreshTask = null;
let lastBgRefreshAt = 0;

function bgDelayOf(saved) {
  // Test hook only: lets harness shorten the delay. Production uses the const.
  let hook;
  try { hook = globalThis.__cnradioTest; } catch (err) { /* no globalThis */ }
  if (hook && typeof hook.bgDelay === "number") return hook.bgDelay;
  return saved && saved.partial ? BG_REFRESH_DELAY_STALE : BG_REFRESH_DELAY;
}

function scheduleBackgroundRefresh(saved) {
  if (bgTimer !== null || refreshTask) return;                 // already queued/running
  if (Date.now() - lastBgRefreshAt < CATALOG_TTL) return;       // at most once per TTL
  let hook;
  try { hook = globalThis.__cnradioTest; } catch (err) { /* no globalThis */ }
  if (hook) hook.bgScheduled = true;                            // observable in tests
  bgTimer = setTimeout(() => {
    bgTimer = null;
    lastBgRefreshAt = Date.now();
    refreshCatalog().catch((err) => {
      // Silent by design: never surface a background failure to the listener.
      console.log("cnradio: background refresh failed: " + err.message);
    });
  }, bgDelayOf(saved));
  // Do not keep a test/CLI process alive just for the timer.
  if (bgTimer && typeof bgTimer.unref === "function") bgTimer.unref();
}

function commitBuild(built, saved) {
  if (!built.partial) {
    host.storage.set("catalog", built);
    return built;
  }
  // Keep the last complete directory instead of caching a hole, but report
  // which regions failed so a manual refresh is honest about it.
  if (saved && !saved.partial && saved.stations &&
      saved.stations.length >= built.stations.length) {
    return Object.assign({}, saved, { refreshFailures: built.failures });
  }
  host.storage.set("catalog", built); // partial, only trusted for PARTIAL_TTL
  return built;
}

async function refreshCatalog() {
  if (refreshTask) return refreshTask; // manual refresh reuses an in-flight build
  refreshTask = (async () => {
    const saved = host.storage.get("catalog");
    const built = await buildCatalog(saved);
    return commitBuild(built, saved);
  })();
  try {
    return await refreshTask;
  } finally {
    refreshTask = null;
  }
}

async function catalog(force) {
  const saved = host.storage.get("catalog");
  // Cache-first: any usable local directory answers immediately. TTL is only
  // a hint for scheduling a background refresh, never a reason to block.
  if (!force && saved && saved.at && saved.stations && saved.stations.length > 20) {
    scheduleBackgroundRefresh(saved);
    return saved;
  }
  // No usable cache yet: build synchronously (there is nothing else to show).
  return refreshCatalog();
}

function idsOf(c, ids, limit) {
  const out = [];
  const list = ids || [];
  const n = limit ? Math.min(limit, list.length) : list.length;
  for (let i = 0; i < n; i++) {
    const s = c.byId[list[i]];
    if (s) out.push(s);
  }
  return out;
}

// Public track/station ids: 云听 stays "live:<id>" (backward compatible),
// 喜马拉雅 uses "xm:<id>". Both may carry a "~<instance>" suffix.
function publicId(s) {
  return s.source === "xm" ? s.id : "live:" + s.id;
}

function toStation(s) {
  return {
    id: publicId(s),
    name: s.name,
    description: s.subtitle || s.region,
    imageUrl: s.image,
  };
}

function toTrack(s) {
  const pid = publicId(s);
  return {
    id: pid,
    name: s.name,
    artists: [{ id: pid, name: s.subtitle || s.region }],
    album: { id: pid, name: s.region, imageUrl: s.image },
    duration: 0,
    imageUrl: s.image,
  };
}

// Return the catalog key (byId) from a public id, preserving the source.
function stationKeyOf(id) {
  const raw = String(id).split("~")[0];
  if (raw.indexOf("xm:") === 0) return raw;
  return raw.replace(/^live:/, "");
}

// ------------------------------------------------------------------ app views

export async function home() {
  const c = await catalog();
  const national = idsOf(c, c.regionIds["0"]);
  const popular = idsOf(c, c.catIds["7"]);
  return {
    title: t("Radio"),
    subtitle: t("Live radio, sorted by category and region"),
    sections: [
      {
        title: t("National stations"),
        stations: national.slice(0, 12).map(toStation),
        morePage: "region:0",
      },
      {
        title: t("Popular"),
        stations: (popular.length ? popular : national).slice(0, 12).map(toStation),
        morePage: "cat:7",
      },
      {
        title: t("By region"),
        links: c.provinces.map((p) => ({
          title: p.provinceName,
          subtitle: (c.regionIds[String(p.provinceCode)] || []).length + t(" stations"),
          page: "region:" + p.provinceCode,
        })),
      },
      {
        title: t("By category"),
        links: c.categories.map((k) => ({
          title: k.categoryName,
          page: "cat:" + k.id,
        })),
      },
      {
        title: t("Replay"),
        links: [{ title: t("Today's programmes"), subtitle: t("Catch up on past programmes"), page: "replay" }],
      },
    ],
  };
}

export async function stations() {
  const c = await catalog();
  return c.stations.map(toStation);
}

function today() {
  const d = new Date();
  const m = d.getMonth() + 1;
  const day = d.getDate();
  return d.getFullYear() + "-" + (m < 10 ? "0" + m : m) + "-" + (day < 10 ? "0" + day : day);
}

// Replay URLs are kept so stream() can answer them later. They live in module
// memory and in host.storage, so they survive an app restart.
let programmeMap = host.storage.get("programmeMap") || {};

function saveProgrammes() {
  const keys = Object.keys(programmeMap);
  if (keys.length > 600) {
    const keep = keys.slice(-400);
    const next = {};
    keep.forEach((k) => { next[k] = programmeMap[k]; });
    programmeMap = next;
  }
  host.storage.set("programmeMap", programmeMap);
}

async function replayChannels() {
  const saved = host.storage.get("replayChannels");
  if (saved && saved.length) return saved;
  const res = await api(API.listenback, { provinceCode: "" });
  const list = res.data || [];
  host.storage.set("replayChannels", list);
  return list;
}

async function replayPage(c, broadcastId) {
  const date = today();
  const res = await api(API.programs, { date: date, broadcastId: String(broadcastId) });
  const items = res.data || [];
  const station = c.byId[String(broadcastId)];
  const stationName = station ? station.name : t("Radio");
  const tracks = [];
  items.forEach((p) => {
    const high = p.playUrlHigh || "";
    const low = p.playUrlLow || "";
    if (!high && !low) return;
    const id = "prog:" + p.id;
    programmeMap[id] = { high: high, low: low, name: p.programName };
    tracks.push({
      id: id,
      name: p.programName,
      artists: [{ id: "live:" + broadcastId, name: stationName }],
      album: { id: "live:" + broadcastId, name: stationName, imageUrl: p.image || "" },
      duration: Math.max(0, (p.endTime || 0) - (p.startTime || 0)),
      imageUrl: p.image || "",
    });
  });
  saveProgrammes();
  return {
    title: stationName,
    subtitle: date + " · " + t("Replay"),
    sections: [{ title: t("Today's programmes"), tracks: tracks, empty: t("No programmes for this date") }],
  };
}

export async function browse({ page }) {
  const p = String(page || "");
  // A visible way to rebuild the directory after a rate-limited or stale load.
  if (p === "refresh") {
    const fresh = await catalog(true);
    const failed = fresh.refreshFailures || fresh.failures || [];
    return {
      title: t("Refresh catalogue"),
      subtitle: fresh.stations.length + t(" stations") +
        (failed.length ? " · " + t("Some regions failed, kept the last complete directory") +
          " [" + failed.join(", ") + "]" : ""),
      sections: [{
        title: t("By region"),
        links: [{ title: t("Refresh catalogue"), subtitle: t("Rebuild the station directory"), page: "refresh" }].concat(
          fresh.provinces.map((x) => ({
            title: x.provinceName,
            subtitle: (fresh.regionIds[String(x.provinceCode)] || []).length + t(" stations"),
            page: "region:" + x.provinceCode,
          }))
        ),
      }],
    };
  }
  const c = await catalog();
  if (p === "regions") {
    return {
      title: t("By region"),
      sections: [{
        title: t("By region"),
        links: [{ title: t("Refresh catalogue"), subtitle: t("Rebuild the station directory"), page: "refresh" }].concat(
          c.provinces.map((x) => ({
            title: x.provinceName,
            subtitle: (c.regionIds[String(x.provinceCode)] || []).length + t(" stations"),
            page: "region:" + x.provinceCode,
          }))
        ),
      }],
    };
  }
  if (p === "categories") {
    return {
      title: t("By category"),
      sections: [{
        title: t("By category"),
        links: c.categories.map((x) => ({ title: x.categoryName, page: "cat:" + x.id })),
      }],
    };
  }
  if (p === "replay") {
    const channels = await replayChannels();
    return {
      title: t("Replay"),
      subtitle: t("Past programmes from 19 channels"),
      sections: [{
        title: t("Today's programmes"),
        links: channels.map((x) => ({ title: x.broadcastName, page: "playback:" + x.id })),
      }],
    };
  }
  if (p.indexOf("region:") === 0 || p.indexOf("cat:") === 0) {
    const isRegion = p.indexOf("region:") === 0;
    const key = p.split(":")[1];
    const ids = isRegion ? c.regionIds[key] : c.catIds[key];
    const label = isRegion
      ? (c.provinces.filter((x) => String(x.provinceCode) === key)[0] || {}).provinceName
      : (c.categories.filter((x) => String(x.id) === key)[0] || {}).categoryName;
    const local = idsOf(c, ids);
    const sections = [{
      title: (label || p) + " · " + t("Local stations"),
      stations: local.map(toStation),
    }];
    // National stations are receivable in every region. Listing them here
    // keeps a region page from looking "incomplete" compared with the radio.
    if (isRegion && key !== "0") {
      const national = idsOf(c, c.regionIds["0"], 19);
      if (national.length) {
        sections.push({
          title: t("National stations (available everywhere)"),
          stations: national.map(toStation),
        });
      }
    }
    return {
      title: label || p,
      subtitle: local.length + t(" stations") +
        (sections.length > 1 ? " · +" + sections[1].stations.length + " " + t("National stations") : ""),
      sections: sections,
    };
  }
  if (p.indexOf("playback:") === 0) return replayPage(c, p.split(":")[1]);
  if (p === "all") {
    return {
      title: t("All stations"),
      sections: [{ title: t("All stations"), stations: c.stations.map(toStation) }],
    };
  }
  throw fail("unavailable", t("No such page"));
}

export async function search({ query, kind, offset }) {
  if (kind && kind !== "tracks") {
    const empty = {};
    empty[kind] = [];
    empty.more = false;
    return empty;
  }
  const c = await catalog();
  const q = String(query || "").toLowerCase();
  const found = c.stations.filter((s) =>
    s.name.toLowerCase().indexOf(q) >= 0 ||
    (s.subtitle || "").toLowerCase().indexOf(q) >= 0 ||
    s.region.toLowerCase().indexOf(q) >= 0
  );
  const start = offset || 0;
  const tracks = found.slice(start, start + PAGE_SIZE).map(toTrack);
  return { tracks: tracks, more: start + PAGE_SIZE < found.length };
}

export async function suggest({ query }) {
  const c = await catalog();
  const q = String(query || "").toLowerCase();
  return c.stations
    .map((s) => s.name)
    .filter((n) => n.toLowerCase().indexOf(q) === 0)
    .slice(0, 10);
}

// A station is endless: each time the player's stream ends, it asks for the
// next track. A fresh id makes it a new track rather than a repeat.
let instance = 0;

export async function stationNext({ id }) {
  const c = await catalog();
  const sid = stationKeyOf(id);
  const s = c.byId[sid];
  if (!s) throw fail("unavailable", t("This station is gone"));
  instance += 1;
  const pid = publicId(s);
  return [{
    id: pid + "~" + instance,
    name: s.name,
    artists: [{ id: pid, name: s.subtitle || s.region }],
    album: { id: pid, name: s.name, imageUrl: s.image },
    duration: 0,
    imageUrl: s.image,
  }];
}

// ------------------------------------------------------------------- playback

function pickLiveUrl(item, low) {
  const order = low
    ? ["mp3PlayUrlLow", "playUrlLow", "mp3PlayUrlHigh", "playUrlMulti"]
    : ["playUrlMulti", "mp3PlayUrlHigh", "mp3PlayUrlLow", "playUrlLow"];
  for (let i = 0; i < order.length; i++) {
    if (item[order[i]]) return item[order[i]];
  }
  return "";
}

// Stream URLs carry a short-lived key/time signature, so always re-query the
// station's own region list instead of reusing a cached one.
async function liveHls(sid, low) {
  const c = await catalog();
  const st = c.byId[sid];
  if (!st) throw fail("unavailable", t("This station is gone"));
  const code = st.provinceCode || "0";
  const res = await api(API.stations, { categoryId: "0", provinceCode: String(code) });
  let item = (res.data || []).filter((x) => String(x.contentId) === sid)[0];
  if (!item) {
    const provinces = c.provinces;
    for (let i = 0; i < provinces.length && !item; i++) {
      const r = await api(API.stations, { categoryId: "0", provinceCode: String(provinces[i].provinceCode) });
      item = (r.data || []).filter((x) => String(x.contentId) === sid)[0];
    }
  }
  if (!item) throw fail("unavailable", t("Stream not found"));
  const url = pickLiveUrl(item, low);
  if (!url) throw fail("unavailable", t("This station has no stream"));
  return url;
}

// 喜马拉雅 stream URLs are stable, but re-query the detail endpoint on every
// play so a changed feed is picked up. aac* segments are raw ADTS.
async function xmLiveHls(sid, low) {
  const c = await catalog();
  const st = c.byId[sid];
  if (!st) throw fail("unavailable", t("This station is gone"));
  const info = await xmRetry(XM_LIVE_BASE + "/live-web/v1/radio", { radioId: st.xmId });
  const urls = (info && info.playUrl) || {};
  const pick = low
    ? (urls.aac24 || urls.aac64 || urls.ts24 || urls.ts64)
    : (urls.aac64 || urls.aac24 || urls.ts64 || urls.ts24);
  if (!pick) throw fail("unavailable", t("This station has no stream"));
  return { url: String(pick).replace(/^http:/, "https:"), rawAac: /\.aac|\/24\.m3u8|\/64\.m3u8/.test(String(pick)) && String(pick).indexOf("transcode=ts") < 0 };
}

let bridgeState = null;

function killBridge() {
  if (bridgeState && bridgeState.proc) {
    try { bridgeState.proc.kill(); } catch (err) { /* already gone */ }
  }
  bridgeState = null;
}

export function setSetting({ key, value }) {
  if (key === "bridge" || key === "pythonCmd" || key === "ffmpegCmd" || key === "quality") {
    killBridge();
  }
  console.log("cnradio: setting " + key + " = " + value);
}

function bridgeCandidates(mode) {
  const pySetting = host.setting("pythonCmd");
  const ffSetting = host.setting("ffmpegCmd");
  const pys = pySetting ? [pySetting] : ["python3", "python", "py"];
  const ffs = ffSetting ? [ffSetting] : ["ffmpeg"];
  const py = pys.map((cmd) => ({ cmd: cmd, kind: "python" }));
  const ff = ffs.map((cmd) => ({ cmd: cmd, kind: "ffmpeg" }));
  if (mode === "python") return py;
  if (mode === "ffmpeg") return ff;
  return py.concat(ff); // auto
}

function scriptPath() {
  let dir = String(host.dir || "").split("\\").join("/");
  while (dir.length > 1 && dir.charAt(dir.length - 1) === "/") dir = dir.slice(0, -1);
  return dir + "/bridge.py";
}

async function ensureBridge(sid, hls, mode, source) {
  const rawAac = !!(source && source.rawAac);
  const referer = rawAac ? XM_REFERER : REFERER;
  if (mode !== "ffmpeg" && bridgeState && bridgeState.sid === sid && bridgeState.kind === "python") {
    return bridgeState; // Python bridge serves any number of connections
  }
  killBridge();
  const candidates = bridgeCandidates(mode);
  const errors = [];
  for (let i = 0; i < candidates.length; i++) {
    const cand = candidates[i];
    const port = 21000 + Math.floor(Math.random() * 9000);
    let args;
    if (cand.kind === "python") {
      args = [scriptPath(), "--url", hls, "--port", String(port),
        "--mode", rawAac ? "aac" : "ts", "--referer", referer];
    } else {
      args = [
        "-hide_banner", "-loglevel", "error",
        "-user_agent", "MyGoMusic/1.0",
        "-headers", "Referer: " + REFERER + "\r\n",
        "-i", hls, "-vn", "-c:a", "copy", "-f", "adts",
        "-listen", "1", "http://127.0.0.1:" + port + "/live.aac",
      ];
    }
    let proc;
    try {
      proc = host.spawn(cand.cmd, args);
    } catch (err) {
      errors.push(cand.cmd + ": " + err.message);
      continue;
    }
    if (!proc) {
      errors.push(cand.cmd + ": no process");
      continue;
    }
    if (cand.kind === "python") {
      let ready = false;
      for (let n = 0; n < 30 && !ready; n++) {
        try {
          const res = await fetch("http://127.0.0.1:" + port + "/health");
          const body = await res.text();
          if (String(body).indexOf("cnradio-bridge-ok") >= 0) ready = true;
        } catch (err) { /* not listening yet */ }
        if (!ready) await sleep(200);
      }
      if (!ready) {
        try { proc.kill(); } catch (err) { /* already gone */ }
        errors.push(cand.cmd + ": bridge did not start");
        continue;
      }
    } else {
      await sleep(900); // ffmpeg's http muxer waits for its first client
    }
    bridgeState = {
      sid: sid,
      kind: cand.kind,
      proc: proc,
      port: port,
      url: "http://127.0.0.1:" + port + "/live.aac",
    };
    return bridgeState;
  }
  throw fail("setup",
    t("Live streams need a bridge. Install Python 3 or ffmpeg, allow the process permission in Settings, then try again.") +
    " (" + errors.join("; ") + ")");
}

export async function stream({ id, quality }) {
  const raw = String(id).split("~")[0];
  const low = quality === "low" || host.setting("quality") === "low";

  // Replay: an ordinary .m4a file, decoded by the app with no help needed.
  if (raw.indexOf("prog:") === 0) {
    let p = programmeMap[raw];
    if (!p) {
      const saved = host.storage.get("programmeMap") || {};
      p = saved[raw];
    }
    if (!p) throw fail("unavailable", t("Programme expired, open the replay page again"));
    const url = low ? (p.low || p.high) : (p.high || p.low);
    return { url: url, type: "m4a", headers: { "Referer": REFERER } };
  }

  const sid = stationKeyOf(raw);
  const isXm = raw.indexOf("xm:") === 0;
  const mode = host.setting("bridge") || "auto";

  let hls;
  let source = { rawAac: false };
  if (isXm) {
    source = await xmLiveHls(sid, low);
    hls = source.url;
  } else {
    hls = await liveHls(sid, low);
  }

  // Debug only: MyGo Music's ADTS decoder rejects a playlist (first byte is '#').
  if (mode === "direct") return { url: hls, type: "aac" };

  const bridge = await ensureBridge(sid, hls, mode, source);
  return { url: bridge.url, type: "aac" };
}
