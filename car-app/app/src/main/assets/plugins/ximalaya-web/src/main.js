// MyGo Music entry point. All public callbacks are declared at the bottom.
import { pluginError } from "./utils.js";
import { signedIn } from "./session.js";
import { account, requireAccount, signIn, signOut } from "./auth.js";
import {
  categoriesPage, categoryPage, discoverPage, history, historyPage, hotSearchPage, hotWords,
  localRecent, played, purchasePage, rankPage, recommendations, search, show, subscriptionPage, subscriptions, purchases, viewedAlbums,
} from "./catalog.js";
import { stream } from "./playback.js";

async function home() {
  const loggedIn = signedIn();
  const viewed = viewedAlbums();
  const listening = localRecent();
  // Independent sections: one failed endpoint must not erase the rest of the
  // page or the user's local records.
  const [recommended, ranked, hot, subscribed, purchased, listened] = await Promise.allSettled([
    recommendations(),
    rankPage(),
    hotWords(),
    loggedIn ? subscriptionPage(1) : Promise.resolve(null),
    loggedIn ? purchasePage(1) : Promise.resolve(null),
    loggedIn ? historyPage(1) : Promise.resolve(null),
  ]);

  const sections = [{
    title: "发现专辑",
    shows: recommended.status === "fulfilled" ? recommended.value.slice(0, 12) : [],
    empty: recommended.status === "rejected" ? `推荐暂不可用：${recommended.reason.message}` : "暂无推荐专辑",
    morePage: "discover",
  }];
  const rankedShows = ranked.status === "fulfilled" ? ranked.value.sections[0]?.shows || [] : [];
  sections.push({ title: "榜单精选", shows: rankedShows.slice(0, 10), empty: "榜单暂不可用", morePage: "rank" });
  sections.push({ title: "按分类浏览", links: [{ title: "全部分类", page: "categories" }, { title: "全站专辑", page: "category:0" }] });
  if (hot.status === "fulfilled" && hot.value.length) {
    sections.push({ title: "热门搜索", links: hot.value.slice(0, 6).map((word) => ({ title: word, page: `search:${encodeURIComponent(word)}` })) });
  }
  if (loggedIn) {
    sections.push({
      title: "我的订阅",
      shows: subscribed.status === "fulfilled" ? subscribed.value.shows.slice(0, 12) : [],
      empty: subscribed.status === "rejected" ? `暂时无法加载：${subscribed.reason.message}` : "当前账号还没有订阅专辑",
      morePage: "subscriptions",
    });
    sections.push({
      title: "已购专辑",
      shows: purchased.status === "fulfilled" ? purchased.value.shows.slice(0, 12) : [],
      empty: purchased.status === "rejected" ? `暂时无法加载：${purchased.reason.message}` : "当前账号没有单独购买的专辑；VIP 内容可通过搜索或订阅打开",
      morePage: "purchases",
    });
    sections.push({
      title: "最近收听",
      tracks: listened.status === "fulfilled" ? listened.value.tracks.slice(0, 10) : [],
      empty: listened.status === "rejected" ? `云端历史暂不可用：${listened.reason.message}` : "云端还没有收听记录",
      morePage: "recent",
    });
  }
  if (listening.length) sections.push({ title: "本机记录", tracks: listening.slice(0, 10), morePage: "local-recent" });
  if (viewed.length) sections.push({ title: "浏览过的专辑", shows: viewed });
  return { title: "喜马拉雅", subtitle: "搜索声音与节目专辑，也可粘贴专辑或单集链接", sections };
}

async function browse({ page } = {}) {
  const target = String(page ?? "");
  if (target === "local-recent") return { title: "本机记录", subtitle: "此设备的收听记录", sections: [{ tracks: localRecent(), empty: "还没有收听记录" }] };
  if (target === "account") {
    const profile = await requireAccount();
    return {
      title: profile.name,
      imageUrl: profile.imageUrl,
      sections: [{ links: [
        { title: "我的订阅", page: "subscriptions" },
        { title: "已购专辑", page: "purchases" },
        { title: "最近收听", page: "recent" },
        { title: "本机记录", page: "local-recent" },
      ] }],
    };
  }
  if (target === "discover") return discoverPage();
  if (target === "rank") return rankPage();
  if (target === "categories") return categoriesPage();

  let match = /^category:(\d{1,9})(?::([1-9]\d{0,3}))?$/.exec(target);
  if (match) return categoryPage(match[1], match[2] ? Number(match[2]) : 1);
  match = /^search:([^:]+)(?::(\d+))?$/.exec(target);
  if (match) {
    let keyword = match[1];
    try { keyword = decodeURIComponent(match[1]); } catch { /* use the raw value */ }
    return hotSearchPage(keyword, match[2] ? Number(match[2]) : 0);
  }
  match = /^(subscriptions|purchases|recent)(?::([1-9]\d{0,3}))?$/.exec(target);
  if (match) return ({ subscriptions, purchases, recent: history })[match[1]](match[2] ? Number(match[2]) : 1);
  throw pluginError("unsupported", "没有这个喜马拉雅页面");
}

async function recent() {
  if (!signedIn()) return [];
  return (await historyPage(1)).tracks;
}

export { account, browse, home, played, recent, search, show, signIn, signOut, stream };
