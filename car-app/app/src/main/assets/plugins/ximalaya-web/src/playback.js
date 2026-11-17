// Web-player stream protocol: decrypt, validate, select a playable format.
import { SITE, USER_AGENT, asId, pluginError, positiveNumber } from "./utils.js";
import { aes128EcbDecrypt } from "./aes.js";
import { assertGeneration, generationNow } from "./session.js";
import { apiRequest } from "./client.js";

// Public protocol key from the official web player, not an account credential.
const PLAY_KEY = Uint8Array.from("aaad3e4fd540b0f79dca95606e72bf93".match(/../g), (hex) => parseInt(hex, 16));
const BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const inflight = new Map();

function decodeBase64URL(encoded) {
  const normalized = encoded.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
  if (!/^[A-Za-z0-9+/]+$/.test(normalized) || normalized.length % 4 === 1) throw new Error("Invalid base64");
  const bytes = [];
  let bits = 0;
  let buffer = 0;
  for (const char of normalized) {
    buffer = (buffer << 6) | BASE64_ALPHABET.indexOf(char);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >>> bits) & 255);
    }
  }
  return Uint8Array.from(bytes);
}

export function validateAudioURL(value) {
  let url;
  try { url = new URL(String(value).replace(/^http:/i, "https:")); }
  catch { throw pluginError("unavailable", "喜马拉雅返回了不支持的音频地址"); }
  if (url.protocol !== "https:" || url.username || url.password || (url.port && url.port !== "443") || !url.hostname.endsWith(".xmcdn.com")) {
    throw pluginError("unavailable", "喜马拉雅返回了不支持的音频地址");
  }
  if (/(?:_preview_|\/preview\/|\/sample\/)/i.test(url.pathname)) {
    throw pluginError("unavailable", "当前账号只有试听权限，无法播放完整单集");
  }
  return url.toString();
}

export function decodePlayURL(encoded) {
  try {
    if (typeof encoded !== "string" || !encoded || encoded.length > 16384) throw new Error("Invalid play URL");
    if (/^https?:\/\//i.test(encoded)) return validateAudioURL(encoded);
    const cipher = decodeBase64URL(encoded);
    let plain;
    try { plain = aes128EcbDecrypt(cipher, PLAY_KEY, true); }
    catch { plain = aes128EcbDecrypt(cipher, PLAY_KEY, false); }
    let decoded = "";
    for (const byte of plain) decoded += String.fromCharCode(byte);
    // Strip PKCS#7 bytes when strict validation was bypassed.
    decoded = decoded.replace(/[\x00-\x1f\x7f]+$/, "");
    if (/[^\x20-\x7e]/.test(decoded)) {
      try { decoded = decodeURIComponent(Array.from(plain, (byte) => "%" + byte.toString(16).padStart(2, "0")).join("")); }
      catch { /* keep the byte-wise decoding */ }
    }
    return validateAudioURL(decoded);
  } catch (error) {
    if (error.code === "unavailable") throw error;
    throw pluginError("unavailable", "喜马拉雅返回了不支持的音频地址");
  }
}

export function formatOf(type) {
  const upper = String(type ?? "").toUpperCase();
  if (upper.includes("MP3")) return "mp3";
  if (upper.includes("M4A")) return "m4a";
  if (upper.includes("AAC")) return "aac";
  if (upper.includes("FLAC")) return "flac";
  return "";
}

export function selectStream(body, quality = "high") {
  const track = body?.trackInfo;
  if (!track || !Array.isArray(track.playUrlList) || !track.playUrlList.length) throw pluginError("unavailable", "喜马拉雅未返回可播放的音频");
  if (track.isVideo || track.isPublic === false || track.isDraft) throw pluginError("unavailable", "该单集暂不支持播放");
  // Never infer purchase rights merely from the presence of an encrypted URL.
  if (track.isAuthorized === false || (track.isPaid === true && track.isAuthorized !== true && track.isFree !== true)) {
    throw pluginError("unavailable", "当前账号无完整播放权限，请在喜马拉雅官方客户端确认权限");
  }
  const formats = track.playUrlList.filter((audio) => audio?.url && formatOf(audio.type));
  if (!formats.length) throw pluginError("unavailable", "该单集没有宿主支持的音频格式");
  const flacFormats = formats.filter((audio) => formatOf(audio.type) === "flac");
  // Most web-player tracks have no FLAC at all. When the host asks for lossless
  // and the service offers none, fall back to the high-tier selection instead
  // of failing playback. The returned quality always states what was delivered.
  const effective = quality === "lossless" && !flacFormats.length ? "high" : quality;
  const candidates = effective === "lossless" ? flacFormats : formats;
  if (!candidates.length) throw pluginError("unavailable", "该单集未提供可用的无损音质");
  const target = effective === "standard" ? 0 : 1;
  // Ximalaya's M4A_64 can be HE-AACv2 (SBR/PS), which the host's AAC-LC
  // decoder rejects. Prefer MP3 inside the same service quality tier.
  const codecRank = { mp3: 0, m4a: 1, aac: 2, flac: 3 };
  const rank = (audio) => Math.abs(Number(audio.qualityLevel ?? 1) - target) * 10 + codecRank[formatOf(audio.type)];
  const chosen = [...candidates].sort((a, b) => rank(a) - rank(b))[0];
  const type = formatOf(chosen.type);
  return {
    url: decodePlayURL(chosen.url),
    type,
    size: positiveNumber(chosen.fileSize),
    quality: type === "flac" ? "lossless" : Number(chosen.qualityLevel) === 0 ? "standard" : "high",
    headers: { "User-Agent": USER_AGENT, Referer: SITE + "/" },
    ...(effective !== quality ? { fallbackFrom: quality } : {}),
  };
}

export async function stream({ id, quality = "high" } = {}) {
  const trackId = asId(id);
  if (!["standard", "high", "lossless"].includes(quality)) throw pluginError("unsupported", "不支持的音质选项");
  const generation = generationNow();
  const key = `${generation}:${trackId}:${quality}`;
  if (inflight.has(key)) return inflight.get(key);
  const promise = (async () => {
    const level = quality === "standard" ? 0 : quality === "lossless" ? 3 : 1;
    const body = await apiRequest(`/mobile-playpage/track/v3/baseInfo/${Date.now()}`, {
      device: "web", trackId, trackQualityLevel: level,
    });
    assertGeneration(generation);
    if (!body.trackInfo || String(body.trackInfo.trackId) !== trackId) throw pluginError("unavailable", "喜马拉雅返回的单集不匹配");
    return selectStream(body, quality);
  })();
  inflight.set(key, promise);
  try { return await promise; }
  finally { if (inflight.get(key) === promise) inflight.delete(key); }
}
