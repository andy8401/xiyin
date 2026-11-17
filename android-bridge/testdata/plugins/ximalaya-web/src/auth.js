// Official web login (QR code in a host-owned window) and account state.
import { LOGIN_PAGE, SITE, USER_AGENT, asId, cleanText, coverURL, pluginError } from "./utils.js";
import { apiRequest } from "./client.js";
import { AUTH_COOKIE, assertGeneration, beginSignIn, clearSession, commitSession, currentSession, parseCookieHeader, signedIn } from "./session.js";

async function profileFor(target) {
  const { data } = await apiRequest("/revision/main/getCurrentUser", {}, { session: target });
  if (!data?.uid || !signedIn(target)) throw pluginError("signIn", "无法确认喜马拉雅登录状态，请重新登录");
  return {
    id: asId(data.uid),
    name: cleanText(data.nickname || data.nickName || data.name) || "喜马拉雅用户",
    imageUrl: coverURL(data.coverPath || data.avatar || data.logoPic || data.imgUrl),
    page: "account",
  };
}

export async function account() {
  const target = currentSession();
  if (!signedIn(target)) return null;
  if (target.profile && Date.now() - target.checkedAt < 60000) return target.profile;
  try {
    const profile = await profileFor(target);
    target.profile = profile;
    target.checkedAt = Date.now();
    return profile;
  } catch (error) {
    if (error.code === "signIn") return null;
    throw error;
  }
}

export async function requireAccount() {
  const profile = await account();
  if (!profile) throw pluginError("signIn", "请先登录喜马拉雅账号");
  return profile;
}

export async function signIn() {
  const started = beginSignIn();
  if (typeof host.webSignIn !== "function") throw pluginError("setup", "请更新 MyGo Music 后使用网页登录");
  if (typeof host.signInPrompt === "function") host.signInPrompt({ text: "请在喜马拉雅登录窗口中扫码，并在手机上确认登录" });
  // The QR endpoint is relative to passport.ximalaya.com; on www it returns 404.
  const loginURL = new URL(LOGIN_PAGE);
  loginURL.searchParams.set("fromUri", SITE + "/");
  const result = await host.webSignIn({
    url: loginURL.toString(),
    cookiesOf: SITE + "/",
    until: [AUTH_COOKIE],
    userAgent: USER_AGENT,
  });
  assertGeneration(started);
  const pending = { cookies: parseCookieHeader(result?.cookie), profile: null, checkedAt: 0 };
  if (!signedIn(pending)) throw pluginError("signIn", "登录窗口未返回有效会话，请重新登录");
  pending.profile = await profileFor(pending);
  assertGeneration(started);
  pending.checkedAt = Date.now();
  commitSession(pending, started);
  if (typeof host.signInPrompt === "function") host.signInPrompt({ text: "登录成功" });
  return pending.profile;
}

export function signOut() {
  clearSession();
}
