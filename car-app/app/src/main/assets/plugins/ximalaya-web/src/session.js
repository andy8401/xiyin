// Local, host-scoped session state. Cookies are never sent to audio CDNs.
import { pluginError } from "./utils.js";

export const AUTH_COOKIE = "1&_token";

let active = null;
let generation = 0;

function blankSession() {
  return { cookies: Object.create(null), profile: null, checkedAt: 0 };
}

export function parseCookieHeader(header) {
  const cookies = Object.create(null);
  for (const part of String(header ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0) continue;
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (!/^[!#$%&'*+\-.^_`|~\da-z]+$/i.test(name)) continue;
    if (/[\r\n\x00-\x20\x7f;]/.test(value)) continue;
    if (value) cookies[name] = value;
  }
  return cookies;
}

export function currentSession() {
  if (!active) {
    const saved = host.storage.get("session");
    active = { cookies: parseCookieHeader(typeof saved?.cookie === "string" ? saved.cookie : ""), profile: saved?.profile ?? null, checkedAt: 0 };
  }
  return active;
}

export function cookieHeaderFor(target) {
  return Object.entries(target.cookies).map(([name, value]) => `${name}=${value}`).join("; ");
}

export function persistSession(target) {
  if (target !== active) return;
  if (target.cookies[AUTH_COOKIE]) host.storage.set("session", { cookie: cookieHeaderFor(target), profile: target.profile });
  else host.storage.remove("session");
}

export function clearSession() {
  generation += 1;
  active = blankSession();
  host.storage.remove("session");
}

export function commitSession(target, started) {
  assertGeneration(started);
  active = target;
  persistSession(target);
}

export function signedIn(target = currentSession()) {
  return Boolean(target.cookies[AUTH_COOKIE]);
}

export function generationNow() { return generation; }
export function beginSignIn() { generation += 1; return generation; }

export function assertGeneration(started) {
  if (started !== generation) throw new Error("账号状态已改变，请重试");
}

export function expireSession(target) {
  if (target === active) clearSession();
  throw pluginError("signIn", "喜马拉雅登录状态已失效，请重新登录");
}

// Only path=/ cookies for the Ximalaya web domain are retained.
export function absorbCookies(response, target) {
  if (typeof response.headers?.getSetCookie !== "function") return;
  for (const line of response.headers.getSetCookie()) {
    const fields = line.split(";");
    const pair = fields.shift() ?? "";
    const separator = pair.indexOf("=");
    if (separator <= 0) continue;
    const name = pair.slice(0, separator).trim();
    const value = pair.slice(separator + 1).trim();
    if (!/^[!#$%&'*+\-.^_`|~\da-z]+$/i.test(name)) continue;
    const attributes = Object.create(null);
    for (const field of fields) {
      const [key, ...rest] = field.trim().split("=");
      attributes[key.toLowerCase()] = rest.join("=");
    }
    if (attributes.domain && !["ximalaya.com", "www.ximalaya.com"].includes(attributes.domain.replace(/^\./, "").toLowerCase())) continue;
    if (attributes.path && attributes.path !== "/") continue;
    const expired = attributes["max-age"] !== undefined ? Number(attributes["max-age"]) <= 0 : attributes.expires && Date.parse(attributes.expires) <= Date.now();
    if (expired || !value || /[\r\n\x00-\x20\x7f;]/.test(value)) delete target.cookies[name];
    else target.cookies[name] = value;
  }
}
