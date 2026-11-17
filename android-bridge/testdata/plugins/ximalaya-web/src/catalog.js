// Web catalogue: search, albums, account lists, rankings, categories, history.
import { PAGE_SIZE, asId, asOffset, cleanText, hasNextPage, pageNumberOf, parsePageNumber, parseXimalayaLink, pluginError, positiveNumber } from "./utils.js";
import { apiRequest } from "./client.js";
import { requireAccount } from "./auth.js";
import { assertGeneration, generationNow } from "./session.js";
import { toShow, toTrack } from "./models.js";

const albumCache = new Map();
const trackCache = new Map();

function rememberTrack(track) {
  trackCache.delete(track.id);
  trackCache.set(track.id, track);
  if (trackCache.size > 200) trackCache.delete(trackCache.keys().next().value);
  return track;
}

function rememberAlbum(key, value) {
  albumCache.set(key, { at: Date.now(), value });
  if (albumCache.size > 30) albumCache.delete(albumCache.keys().next().value);
  return value;
}

export async function loadAlbum(id) {
  const albumId = asId(id);
  const generation = generationNow();
  const key = `${generation}:${albumId}`;
  const cached = albumCache.get(key);
  if (cached && Date.now() - cached.at < 120000) return cached.value;
  const { data } = await apiRequest("/revision/album", { albumId });
  if (!data?.mainInfo || !data?.tracksInfo || String(data.albumId) !== albumId) throw new Error("喜马拉雅未返回完整专辑信息");
  assertGeneration(generation);
  const value = toShow({ ...data.mainInfo, albumId, trackTotalCount: data.tracksInfo.trackTotalCount, anchorName: data.anchorInfo?.anchorName });
  return rememberAlbum(key, value);
}

export async function loadTrack(id) {
  const trackId = asId(id);
  const { data } = await apiRequest("/revision/track/simple", { trackId });
  if (!data?.trackInfo || String(data.trackInfo.trackId) !== trackId) throw pluginError("unavailable", "喜马拉雅未返回有效单集信息");
  const info = data.trackInfo;
  const album = data.albumInfo || {};
  return rememberTrack(toTrack({
    ...info,
    albumId: album.albumId ?? info.albumId,
    albumTitle: album.title,
    nickname: info.anchorName || info.nickname,
  }, album));
}

export async function search({ query, kind = "tracks", offset = 0 } = {}) {
  if (!["tracks", "shows"].includes(kind)) throw pluginError("unsupported", "支持搜索声音和节目专辑");
  const start = asOffset(offset);
  const keyword = String(query ?? "").trim();
  if (!keyword) return { [kind]: [], more: false };
  if (keyword.length > 500) throw new Error("搜索内容过长");

  const link = parseXimalayaLink(keyword);
  if (link) {
    // MyGo defaults to its Songs tab, so an album link must work there too.
    if (kind === "tracks" && link.kind === "album") {
      const result = await show({ id: link.id, offset: start });
      return { tracks: result.episodes, more: result.more };
    }
    const matches = (kind === "shows" && link.kind === "album") || (kind === "tracks" && link.kind === "sound");
    if (!matches || start > 0) return { [kind]: [], more: false };
    return { [kind]: [link.kind === "album" ? await loadAlbum(link.id) : await loadTrack(link.id)], more: false };
  }

  const core = kind === "shows" ? "album" : "track";
  const page = pageNumberOf(start);
  const { data } = await apiRequest("/revision/search/seo", { kw: keyword, core, page, rows: PAGE_SIZE });
  const result = data?.[core];
  if (!result || !Array.isArray(result.docs)) throw new Error("喜马拉雅未返回有效搜索结果，请稍后重试");
  const rows = result.docs.slice(start % PAGE_SIZE);
  const items = rows.map((row) => kind === "shows" ? toShow(row) : rememberTrack(toTrack(row)));
  const more = hasNextPage(result, result.docs.length, (page - 1) * PAGE_SIZE + result.docs.length, result.total);
  if (more && items.length === 0) throw new Error("喜马拉雅搜索分页暂不可用，请重新搜索");
  return { [kind]: items, more };
}

export async function show({ id, offset = 0 } = {}) {
  const albumId = asId(id);
  const start = asOffset(offset);
  const generation = generationNow();
  const page = pageNumberOf(start);
  const [album, response] = await Promise.all([
    loadAlbum(albumId),
    apiRequest("/revision/play/v1/show", { id: albumId, ptype: 0, num: page, size: PAGE_SIZE, sort: 0 }),
  ]);
  assertGeneration(generation);
  const data = response.data;
  if (!data || String(data.albumId) !== albumId || !Array.isArray(data.tracksAudioPlay) || Number(data.pageNum) !== page) throw new Error("喜马拉雅返回了无效专辑分页");
  const episodes = data.tracksAudioPlay.slice(start % PAGE_SIZE).map((row) => rememberTrack(toTrack(row, album)));
  const more = hasNextPage(data, data.tracksAudioPlay.length, (page - 1) * PAGE_SIZE + data.tracksAudioPlay.length, album.episodeCount);
  if (more && !episodes.length) throw new Error("喜马拉雅专辑分页暂不可用，请重新打开专辑");
  if (start === 0) {
    const previous = host.storage.get("viewedShows");
    const saved = Array.isArray(previous) ? previous.filter((item) => item?.id && item.id !== albumId) : [];
    host.storage.set("viewedShows", [album, ...saved].slice(0, 20));
  }
  return { show: album, episodes, more };
}

export async function subscriptionPage(page = 1) {
  const current = parsePageNumber(page, "订阅");
  await requireAccount();
  const { data } = await apiRequest("/revision/my/getSubscriptionSynthesize", { pageNum: current, pageSize: PAGE_SIZE });
  // The live endpoint returns `page`, unlike the purchased-albums endpoint.
  if (!data || !Array.isArray(data.albumsInfo) || Number(data.page ?? data.pageNum) !== current) throw new Error("喜马拉雅未返回有效订阅列表");
  const shows = data.albumsInfo.map((row) => toShow(row, { subscribed: true }));
  const more = hasNextPage(data, shows.length, (current - 1) * PAGE_SIZE + shows.length, data.totalCount);
  if (more && !shows.length) throw new Error("喜马拉雅订阅分页暂不可用，请重新打开");
  return { shows, more };
}

export async function subscriptions(page = 1) {
  const { shows, more } = await subscriptionPage(page);
  return {
    title: "我的订阅",
    subtitle: "喜马拉雅账号订阅（只读）",
    sections: [{ title: "订阅专辑", shows, empty: "还没有订阅专辑", ...(more ? { morePage: `subscriptions:${page + 1}` } : {}) }],
  };
}

export async function purchasePage(page = 1) {
  const current = parsePageNumber(page, "已购");
  await requireAccount();
  const { data } = await apiRequest("/revision/my/getHasBroughtAlbums", { pageNum: current, pageSize: PAGE_SIZE });
  if (!data || !Array.isArray(data.albumList) || Number(data.pageNum ?? data.page) !== current) throw new Error("喜马拉雅未返回有效已购列表");
  const shows = data.albumList.map((row) => toShow(row));
  const more = hasNextPage(data, shows.length, (current - 1) * PAGE_SIZE + shows.length, data.totalCount);
  if (more && !shows.length) throw new Error("喜马拉雅已购分页暂不可用，请重新打开");
  return { shows, more };
}

export async function purchases(page = 1) {
  const { shows, more } = await purchasePage(page);
  return {
    title: "已购专辑",
    subtitle: "单独购买的专辑；VIP 可听内容请通过搜索或订阅打开",
    sections: [{ title: "已购专辑", shows, empty: "当前账号没有单独购买的专辑；VIP 权益内容可通过搜索或订阅打开", ...(more ? { morePage: `purchases:${page + 1}` } : {}) }],
  };
}

export async function recommendations() {
  const { data } = await apiRequest("/revision/explore/guessYouLike", { page: 1, pageSize: 30 });
  if (!data || !Array.isArray(data.recommendInfoList)) throw new Error("喜马拉雅未返回有效推荐列表");
  return data.recommendInfoList.filter((row) => row && !row.isDeleted).slice(0, 60).map((row) => toShow(row));
}

export async function rankPage() {
  const { data } = await apiRequest("/revision/rank/getRankList");
  const sections = [];
  for (const [key, fallback] of [["freeAlbumRank", "免费经典榜"], ["paidAlbumRank", "付费畅销榜"]]) {
    const block = data?.[key];
    if (!block || !Array.isArray(block.rankListInfo)) continue;
    const shows = block.rankListInfo.filter((row) => row?.id).slice(0, 30).map((row) => toShow(row));
    sections.push({ title: cleanText(block.rankListTitle) || fallback, shows, empty: "榜单暂时为空" });
  }
  // The anchor board contains host profiles, not playable albums, so it is
  // deliberately not rendered as a show list.
  if (!sections.length) throw new Error("喜马拉雅未返回有效榜单");
  return { title: "喜马拉雅榜单", subtitle: "官网排行榜 · 免费榜 / 付费榜", sections };
}

export async function categoryList() {
  const { data } = await apiRequest("/revision/category/v2/allCategory");
  if (!data || !Array.isArray(data.categories)) throw new Error("喜马拉雅未返回有效分类列表");
  return data.categories
    .filter((row) => row && row.isDisplay !== false && !row.parentId)
    .map((row) => ({ id: String(row.id), name: cleanText(row.title || row.name) || "未命名分类" }));
}

export async function categoriesPage() {
  const categories = await categoryList();
  return {
    title: "分类",
    subtitle: "按官网分类浏览专辑",
    sections: [{
      title: "全部分类",
      links: [{ title: "全站专辑", page: "category:0" }, ...categories.map((row) => ({ title: row.name, page: `category:${row.id}` }))],
      empty: "暂无分类",
    }],
  };
}

export async function categoryPage(categoryId, page = 1) {
  const category = String(categoryId ?? "0");
  if (!/^\d{1,9}$/.test(category)) throw new Error("无效的分类 ID");
  const current = parsePageNumber(page, "分类");
  const params = { pageNum: current, pageSize: PAGE_SIZE, sort: 1 };
  if (category !== "0") params.categoryId = category;
  const { data } = await apiRequest("/revision/category/v2/albums", params);
  if (!data || !Array.isArray(data.albums) || (data.pageNum !== undefined && Number(data.pageNum) !== current)) throw new Error("喜马拉雅未返回有效分类专辑");
  const shows = data.albums.filter((row) => row && !row.isDeleted).map((row) => toShow(row));
  const total = Number(data.total);
  const more = Number.isFinite(total) && total >= 0 ? (current - 1) * PAGE_SIZE + data.albums.length < total : shows.length === PAGE_SIZE;
  return {
    title: category === "0" ? "全站专辑" : "分类专辑",
    subtitle: `分类 ID ${category} · 第 ${current} 页`,
    sections: [{ title: "专辑", shows, empty: "该分类暂无专辑", ...(more ? { morePage: `category:${category}:${current + 1}` } : {}) }],
  };
}

export async function hotWords() {
  const { data } = await apiRequest("/revision/search/hotWord");
  if (!data || !Array.isArray(data.hots)) throw new Error("喜马拉雅未返回有效热搜词");
  return data.hots.filter((word) => typeof word === "string" && word.trim()).slice(0, 10).map((word) => cleanText(word));
}

export async function hotSearchPage(keyword, offset = 0) {
  const word = String(keyword ?? "").trim().slice(0, 100);
  if (!word) throw pluginError("unsupported", "没有这个喜马拉雅页面");
  const start = asOffset(offset);
  const result = await search({ query: word, kind: "tracks", offset: start });
  return {
    title: `搜索 · ${word}`,
    subtitle: "来自官网热搜词",
    sections: [{ title: "相关声音", tracks: result.tracks, empty: "没有找到相关声音", ...(result.more ? { morePage: `search:${encodeURIComponent(word)}:${start + result.tracks.length}` } : {}) }],
  };
}

export async function discoverPage() {
  const [recommended, hot] = await Promise.allSettled([recommendations(), hotWords()]);
  const sections = [{
    title: "喜马拉雅推荐",
    shows: recommended.status === "fulfilled" ? recommended.value.slice(0, 30) : [],
    empty: recommended.status === "rejected" ? `推荐暂不可用：${recommended.reason.message}` : "暂无推荐专辑",
  }];
  if (hot.status === "fulfilled" && hot.value.length) {
    sections.push({ title: "热门搜索", links: hot.value.map((word) => ({ title: word, page: `search:${encodeURIComponent(word)}` })) });
  }
  return { title: "发现", subtitle: "官网推荐与热门搜索", sections };
}

function clockTime(value) {
  const seconds = Math.floor(Math.max(0, Number(value) || 0));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  const rest = String(seconds % 60).padStart(2, "0");
  return hours ? `${hours}:${String(minutes).padStart(2, "0")}:${rest}` : `${minutes}:${rest}`;
}

export async function historyPage(page = 1) {
  const current = parsePageNumber(page, "历史");
  await requireAccount();
  const { data } = await apiRequest("/revision/track/history/listen", { includeChannel: false, includeRadio: false, pageNum: current, pageSize: PAGE_SIZE });
  const groups = [["today", "今天"], ["yesterday", "昨天"], ["earlier", "更早"]];
  if (!data || Number(data.pageNum) !== current || groups.some(([key]) => !Array.isArray(data[key]))) throw new Error("喜马拉雅未返回有效云端历史");
  const seen = new Set();
  let received = 0;
  const sections = groups.map(([key, title]) => {
    const rows = data[key];
    received += rows.length;
    const items = [];
    for (const row of [...rows].sort((a, b) => Number(b.startedAt || 0) - Number(a.startedAt || 0))) {
      if (row.isDeleted || row.childIsVideo || Number(row.type) !== 1) continue;
      const id = asId(row.childId);
      if (seen.has(id)) continue;
      seen.add(id);
      const track = toTrack({
        trackId: id,
        trackName: row.childTitle,
        trackCoverPath: row.itemCoverUrl,
        albumId: positiveNumber(row.itemId) ? row.itemId : undefined,
        albumName: row.itemTitle,
        anchorId: positiveNumber(row.uid) ? row.uid : undefined,
        nickname: row.userName,
        duration: row.length,
        isPaid: row.isPaid,
      }, {});
      // The host has no resume-position field for stream tracks, so the last
      // listening point is shown as a description instead.
      if (row.breakSecond !== null && row.breakSecond !== undefined && Number.isFinite(Number(row.breakSecond))) {
        track.description = `上次听到 ${clockTime(row.breakSecond)}`;
      }
      items.push(rememberTrack(track));
    }
    return { title, tracks: items };
  }).filter((section) => section.tracks.length);
  const more = hasNextPage(data, received, (current - 1) * PAGE_SIZE + received, data.totalCount);
  if (more && !received) throw new Error("喜马拉雅历史分页暂不可用，请重新打开");
  return { sections, tracks: sections.flatMap((section) => section.tracks), more };
}

export async function history(page = 1) {
  const result = await historyPage(page);
  const sections = result.sections.length ? result.sections : [{ title: "云端历史", tracks: [], empty: "喜马拉雅云端还没有可播放的历史记录" }];
  if (result.more) sections[sections.length - 1].morePage = `recent:${page + 1}`;
  return { title: "最近收听", subtitle: "喜马拉雅云端历史（只读）", sections };
}

export function localRecent() {
  const records = host.storage.get("recent");
  return Array.isArray(records) ? records.filter((row) => row?.id && row?.album) : [];
}

export function viewedAlbums() {
  const records = host.storage.get("viewedShows");
  return Array.isArray(records) ? records.filter((row) => row?.id) : [];
}

export async function played({ id } = {}) {
  const trackId = asId(id);
  const generation = generationNow();
  const track = trackCache.get(trackId) || await loadTrack(trackId);
  assertGeneration(generation);
  host.storage.set("recent", [track, ...localRecent().filter((item) => item.id !== trackId)].slice(0, 50));
}
