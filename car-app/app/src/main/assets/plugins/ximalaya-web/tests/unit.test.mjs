import test from "node:test";
import assert from "node:assert/strict";
import { createCipheriv } from "node:crypto";
import * as utils from "../src/utils.js";
import { aes128EcbDecrypt } from "../src/aes.js";
import { decodePlayURL, formatOf, selectStream, validateAudioURL } from "../src/playback.js";
import { encryptedURL } from "./helpers.mjs";

const KEY = Buffer.from("aaad3e4fd540b0f79dca95606e72bf93", "hex");

test("utils: links accept only official album/sound forms", () => {
  assert.deepEqual(utils.parseXimalayaLink("album:123"), { kind: "album", id: "123" });
  assert.deepEqual(utils.parseXimalayaLink("sound:456"), { kind: "sound", id: "456" });
  assert.deepEqual(utils.parseXimalayaLink("https://www.ximalaya.com/sound/789/"), { kind: "sound", id: "789" });
  assert.deepEqual(utils.parseXimalayaLink("https://m.ximalaya.com/42/album/789"), { kind: "album", id: "789" });
  assert.equal(utils.parseXimalayaLink("https://evil.com/sound/789"), null);
  assert.equal(utils.parseXimalayaLink("https://www.ximalaya.com/evil/789"), null);
  // Only the pathname is used: query strings cannot redirect the resolved ID.
  assert.deepEqual(utils.parseXimalayaLink("https://www.ximalaya.com/sound/789?albumId=999"), { kind: "sound", id: "789" });
  assert.equal(utils.parseXimalayaLink("javascript:alert(1)"), null);
});

test("utils: cover URLs are restricted to the Ximalaya CDN", () => {
  assert.equal(utils.coverURL("//imagev2.xmcdn.com/a.jpg"), "https://imagev2.xmcdn.com/a.jpg");
  assert.equal(utils.coverURL("storages/a.jpg"), "https://imagev2.xmcdn.com/storages/a.jpg");
  assert.equal(utils.coverURL("http://a.xmcdn.com/a.jpg"), "https://a.xmcdn.com/a.jpg");
  assert.equal(utils.coverURL("https://evil.com/a.jpg"), "");
  assert.equal(utils.coverURL("https://a.xmcdn.com:8443/a.jpg"), "");
  assert.equal(utils.coverURL("https://user:pw@a.xmcdn.com/a.jpg"), "");
  assert.equal(utils.coverURL(""), "");
});

test("utils: text sanitation, ids, offsets and pagination", () => {
  assert.equal(utils.cleanText('<p>你好</p><script>bad()</script> &amp; <em>世界</em>'), "你好\n & 世界");
  assert.equal(utils.cleanText("  a\n\n\n\nb  "), "a\n\nb");
  assert.equal(utils.asId(123456), "123456");
  assert.throws(() => utils.asId("0"), /无效的喜马拉雅 ID/);
  assert.throws(() => utils.asId("../../etc/passwd"), /无效的喜马拉雅 ID/);
  assert.equal(utils.asOffset(undefined), 0);
  assert.throws(() => utils.asOffset(-1), /无效的分页位置/);
  assert.equal(utils.pageNumberOf(0), 1);
  assert.equal(utils.pageNumberOf(30), 2);
  assert.equal(utils.hasNextPage({ hasMore: false }, 30, 30, 1000), false);
  assert.equal(utils.hasNextPage({}, 30, 30, 100), true);
  assert.equal(utils.hasNextPage({}, 5, 5, 5), false);
  assert.equal(utils.parsePageNumber(2, "分类"), 2);
  assert.throws(() => utils.parsePageNumber(0, "分类"), /无效的分类分页/);
  assert.equal(utils.pluginError("unavailable", "x").code, "unavailable");
});

test("aes: pure-JS decryptor matches Node crypto for several lengths", () => {
  for (const plain of ["x", "https://a.xmcdn.com/a.m4a?token=abc", "中文URL参数", "z".repeat(207)]) {
    const cipher = createCipheriv("aes-128-ecb", KEY, null);
    const bytes = Buffer.concat([cipher.update(Buffer.from(plain, "utf8")), cipher.final()]);
    const decrypted = aes128EcbDecrypt(new Uint8Array(bytes), new Uint8Array(KEY));
    assert.equal(Buffer.from(decrypted).toString("utf8"), plain);
  }
  assert.throws(() => aes128EcbDecrypt(new Uint8Array(15), new Uint8Array(KEY)), /invalid ciphertext/);
});

test("playback: encrypted, plain, invalid and preview URLs", () => {
  const url = "https://a.xmcdn.com/storages/f9ce/test.m4a?sign=2cd&buy_key=web_1:0&timestamp=2";
  assert.equal(decodePlayURL(encryptedURL(url)), url);
  assert.equal(decodePlayURL(url), url);
  assert.equal(decodePlayURL("http://a.xmcdn.com/a.mp3"), "https://a.xmcdn.com/a.mp3");
  assert.throws(() => decodePlayURL(encryptedURL("https://evil.com/a.mp3")), /不支持的音频地址/);
  assert.throws(() => decodePlayURL(encryptedURL("https://a.xmcdn.com/sample/a.mp3")), /只有试听权限/);
  assert.throws(() => decodePlayURL("_not_base64!"), /不支持的音频地址/);
  assert.throws(() => validateAudioURL("https://a.xmcdn.com:8443/a.mp3"), /不支持的音频地址/);
  assert.equal(formatOf("M4A_64"), "m4a");
  assert.equal(formatOf("MP3_32"), "mp3");
  assert.equal(formatOf("FLAC"), "flac");
  assert.equal(formatOf("unknown"), "");
});

test("playback: format/quality selection and authorization checks", () => {
  const list = [
    { type: "M4A_64", url: encryptedURL("https://a.xmcdn.com/a.m4a"), qualityLevel: 1, fileSize: 100 },
    { type: "MP3_64", url: encryptedURL("https://a.xmcdn.com/a.mp3"), qualityLevel: 1, fileSize: 200 },
    { type: "MP3_32", url: encryptedURL("https://a.xmcdn.com/low.mp3"), qualityLevel: 0, fileSize: 50 },
    { type: "FLAC", url: encryptedURL("https://a.xmcdn.com/a.flac"), qualityLevel: 3, fileSize: 900 },
  ];
  const body = (flags = {}) => ({ ret: 0, trackInfo: { trackId: 123, isPaid: false, isAuthorized: true, isFree: false, isPublic: true, playUrlList: list, ...flags } });
  const high = selectStream(body(), "high");
  assert.equal(high.type, "mp3", "same-tier MP3 must beat HE-AAC M4A");
  assert.equal(high.quality, "high");
  assert.ok(high.url.endsWith("a.mp3"));
  assert.equal(high.headers.Referer, "https://www.ximalaya.com/");
  assert.equal(selectStream(body(), "standard").quality, "standard");
  assert.equal(selectStream(body(), "lossless").type, "flac");
  assert.throws(() => selectStream(body({ isAuthorized: false }), "high"), /无完整播放权限/);
  assert.throws(() => selectStream(body({ isPaid: true, isAuthorized: false }), "high"), /无完整播放权限/);
  assert.throws(() => selectStream(body({ isVideo: true }), "high"), /暂不支持播放/);
  assert.throws(() => selectStream(body({ playUrlList: [] }), "high"), /未返回可播放的音频/);
  const fallback = selectStream(body({ playUrlList: [list[0]] }), "lossless");
  assert.equal(fallback.quality, "high", "lossless without FLAC must fall back to high");
  assert.equal(fallback.type, "m4a");
  assert.equal(fallback.fallbackFrom, "lossless");
  assert.equal(selectStream(body(), "lossless").quality, "lossless", "real FLAC must stay lossless");
  assert.equal(selectStream(body(), "lossless").fallbackFrom, undefined);
});
