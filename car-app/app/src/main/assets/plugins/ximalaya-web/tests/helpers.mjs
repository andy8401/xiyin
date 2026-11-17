// Test-only helpers: a fake MyGo host, a fake fetch, and web-player ciphertext.
import { createCipheriv } from "node:crypto";

let serial = 0;
const PLAY_KEY = Buffer.from("aaad3e4fd540b0f79dca95606e72bf93", "hex");

export function encryptedURL(url) {
  const cipher = createCipheriv("aes-128-ecb", PLAY_KEY, null);
  return Buffer.concat([cipher.update(url, "utf8"), cipher.final()]).toString("base64url");
}

export function json(body, headers = {}, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

export function audioBody(id = "123", flags = {}, list) {
  return {
    ret: 0,
    trackInfo: {
      trackId: Number(id), isPaid: false, isFree: false, isAuthorized: true, isPublic: true, duration: 120,
      playUrlList: list || [{ type: "M4A_64", url: encryptedURL("https://a.xmcdn.com/test.m4a?token=synthetic"), qualityLevel: 1, fileSize: 1024 }],
      ...flags,
    },
  };
}

export function savedSession() {
  return { session: { cookie: "1&_token=synthetic-old; 1&_uid=42", profile: { id: "42", name: "测试用户" } } };
}

export function profileResponse() {
  return json({ ret: 200, data: { uid: 42, nickname: "测试用户", coverPath: "group/test.jpg" } });
}

export async function loadPlugin(handler = () => { throw new Error("Unexpected network request"); }, saved = {}) {
  const storage = new Map(Object.entries(structuredClone(saved)));
  const requests = [];
  const prompts = [];
  const host = {
    storage: {
      get(key) { return structuredClone(storage.get(key) ?? null); },
      set(key, value) { storage.set(key, structuredClone(value)); },
      remove(key) { storage.delete(key); },
    },
    signInPrompt(value) { prompts.push(value); },
    async webSignIn() { return { cookie: "1&_token=synthetic-login; 1&_uid=42" }; },
  };
  globalThis.host = host;
  globalThis.fetch = async (url, init) => {
    const call = { url: new URL(url), init };
    requests.push(call);
    return handler(call, requests.length);
  };
  const plugin = await import(`../index.js?test=${++serial}`);
  return { plugin, host, storage, requests, prompts };
}

// A complete fake website response set, keyed by pathname.
export function siteHandler(overrides = {}) {
  return (call, index) => {
    const path = call.url.pathname;
    const query = call.url.searchParams;
    const override = overrides[path];
    if (override) return typeof override === "function" ? override(call, index) : override;

    if (path === "/revision/search/seo") {
      const core = query.get("core");
      const docs = core === "album"
        ? [{ albumId: 55, title: "搜索专辑", coverPath: "group/test.jpg", intro: "简介", nickname: "主播", tracksCount: 65 }]
        : [{ id: 1001, title: "搜索声音", duration: 120, nickname: "主播", albumId: 55, albumTitle: "测试专辑", coverPath: "group/test.jpg" }];
      return json({ ret: 200, data: { illegal: false, kw: query.get("kw"), [core]: { docs, total: docs.length } } });
    }
    if (path === "/revision/album") {
      return json({ ret: 200, data: { albumId: 55, mainInfo: { albumTitle: "测试专辑", cover: "group/test.jpg", intro: "专辑简介" }, anchorInfo: { anchorName: "测试主播" }, tracksInfo: { trackTotalCount: 65 } } });
    }
    if (path === "/revision/track/simple") {
      return json({ ret: 200, data: { trackInfo: { trackId: 1001, title: "单集详情", duration: 130, anchorName: "测试主播" }, albumInfo: { albumId: 55, title: "测试专辑" } } });
    }
    if (path === "/revision/play/v1/show") {
      const page = Number(query.get("num") || 1);
      const start = (page - 1) * 30;
      return json({ ret: 200, data: {
        albumId: 55, pageNum: page, pageSize: 30, hasMore: start + 30 < 65,
        tracksAudioPlay: Array.from({ length: Math.min(30, 65 - start) }, (_, i) => ({ trackId: 2000 + start + i, trackName: `第${start + i + 1}集`, duration: 120, albumId: 55, albumName: "测试专辑" })),
      } });
    }
    if (path === "/revision/explore/guessYouLike") {
      return json({ ret: 200, data: { recommendInfoList: [{ albumId: 41, albumTitle: "推荐专辑", albumCoverPath: "group/test.jpg", albumUserNickName: "推荐主播", albumTrackCount: 5, isDeleted: false }] } });
    }
    if (path === "/revision/rank/getRankList") {
      const board = (title, id) => ({ rankListTitle: title, rankId: id, rankListInfo: [{ id: 60 + id, albumTitle: title, cover: "//imagev2.xmcdn.com/group/test.jpg", anchorName: "主播", trackCount: 3, description: "榜单简介" }] });
      return json({ ret: 200, data: { freeAlbumRank: board("免费经典榜", 1), paidAlbumRank: board("付费畅销榜", 2), hotAnchorRank: { rankListInfo: [] } } });
    }
    if (path === "/revision/category/v2/allCategory") {
      return json({ ret: 200, data: { categories: [{ id: 2, title: "音乐", isDisplay: true, parentId: 0 }, { id: 3, title: "有声书", isDisplay: true, parentId: 0 }, { id: 9, title: "隐藏", isDisplay: false, parentId: 0 }] } });
    }
    if (path === "/revision/category/v2/albums") {
      return json({ ret: 200, data: { pageNum: Number(query.get("pageNum") || 1), pageSize: 30, total: 3000, albums: [{ albumId: 71, albumTitle: "分类专辑", albumCoverPath: "group/test.jpg", albumUserNickName: "分类主播", albumTrackCount: 12, isDeleted: false }] } });
    }
    if (path === "/revision/search/hotWord") return json({ ret: 200, data: { hots: ["相声", "成语故事"] } });
    if (path === "/revision/main/getCurrentUser") return profileResponse();
    if (path === "/revision/my/getSubscriptionSynthesize") {
      return json({ ret: 200, data: { page: Number(query.get("pageNum") || 1), albumsInfo: [{ albumId: 81, albumTitle: "订阅专辑", cover: "group/test.jpg" }], totalCount: 1 } });
    }
    if (path === "/revision/my/getHasBroughtAlbums") {
      return json({ ret: 200, data: { pageNum: Number(query.get("pageNum") || 1), albumList: [{ albumId: 82, albumTitle: "已购专辑", cover: "group/test.jpg" }], totalCount: 1 } });
    }
    if (path === "/revision/track/history/listen") {
      return json({ ret: 200, data: {
        pageNum: Number(query.get("pageNum") || 1), totalCount: 1,
        today: [{ childId: 1001, childTitle: "历史单集", itemId: 55, itemTitle: "测试专辑", uid: 42, userName: "测试主播", length: 120, type: 1, startedAt: 1700000000000, breakSecond: 65, isDeleted: false, childIsVideo: false }],
        yesterday: [], earlier: [],
      } });
    }
    if (path.startsWith("/mobile-playpage/track/v3/baseInfo/")) return json(audioBody(query.get("trackId") || "123"));
    throw new Error(`Unexpected network request: ${path}`);
  };
}
