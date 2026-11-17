// One gateway for every www.ximalaya.com call: path hardening, timeout,
// business-code handling, cookie scoping and session invalidation.
import { SITE, USER_AGENT, pluginError } from "./utils.js";
import { absorbCookies, assertGeneration, cookieHeaderFor, currentSession, expireSession, generationNow, persistSession, signedIn } from "./session.js";

const RISK_CODES = new Set([403, 407, 429, 1001]);

export async function apiRequest(path, params = {}, options = {}) {
  if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//") || /[?#\r\n]/.test(path)) throw new Error("无效的请求路径");
  const target = options.session || currentSession();
  const started = generationNow();
  const url = new URL(path, SITE);
  if (url.origin !== SITE) throw new Error("无效的请求地址");
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  const headers = { "User-Agent": USER_AGENT, Referer: SITE + "/", Accept: "application/json" };
  const cookie = cookieHeaderFor(target);
  if (cookie) headers.Cookie = cookie;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  let response;
  let body;
  try {
    response = await fetch(url.toString(), { headers, signal: controller.signal, redirect: "error" });
    assertGeneration(started);
    const wasSignedIn = signedIn(target);
    absorbCookies(response, target);
    persistSession(target);
    if (wasSignedIn && !signedIn(target)) expireSession(target);
    if (response.status === 401) expireSession(target);
    if (!response.ok) throw new Error(`喜马拉雅请求失败（HTTP ${response.status}）`);
    try { body = await response.json(); }
    catch { throw new Error("喜马拉雅返回了无法识别的数据，请稍后重试"); }
  } catch (error) {
    if (started !== generationNow()) throw error.code === "signIn" ? error : new Error("账号状态已改变，请重试");
    if (error.code === "signIn") throw error;
    if (controller.signal.aborted) throw new Error("喜马拉雅请求超时，请稍后重试");
    // Never surface raw fetch errors, response bodies, cookies or signed URLs.
    if (response) throw error;
    throw new Error("无法连接喜马拉雅，请检查网络后重试");
  } finally {
    clearTimeout(timer);
  }

  assertGeneration(started);
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("喜马拉雅返回了无效数据");
  if (body.isNeedLogin || body.needLogin || Number(body.ret) === 401) expireSession(target);

  const code = Number(body.ret);
  if (!Number.isFinite(code) || ![0, 200].includes(code)) {
    if (code === 404 || code === 929) throw pluginError("unavailable", "内容不存在或已下架");
    if (RISK_CODES.has(code)) throw new Error("喜马拉雅要求验证访问，请重新登录，或稍后在官方网页确认可正常访问");
    throw new Error(`喜马拉雅接口暂不可用（${Number.isFinite(code) ? code : "未知"}）`);
  }
  const data = body.data;
  if (data?.riskLevel || data?.reason || data?.illegal === true) throw new Error("喜马拉雅要求完成网页验证，请重新登录后重试");
  return body;
}
