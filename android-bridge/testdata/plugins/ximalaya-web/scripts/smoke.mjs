// Opt-in public smoke test of the built plugin. Read-only, no login, no writes.
// Loads only 32 bytes of one free track to verify the CDN Range response.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const storage = new Map();
globalThis.host = {
  storage: {
    get: (key) => structuredClone(storage.get(key) ?? null),
    set: (key, value) => storage.set(key, structuredClone(value)),
    remove: (key) => storage.delete(key),
  },
};

const plugin = await import(`../index.js?smoke=${randomUUID()}`);
assert.equal(await plugin.account(), null, "fresh plugin must not be signed in");

const home = await plugin.home();
assert.equal(home.title, "喜马拉雅");
assert.ok(home.sections[0].shows.length > 0, "home recommendations must not be empty");
console.log(`home: ${home.sections[0].shows.length} recommended albums; ${home.sections.length} sections`);

for (const kind of ["shows", "tracks"]) {
  const found = await plugin.search({ query: "唐诗", kind, offset: 0 });
  assert.ok(found[kind].length > 0, `${kind} search returned nothing`);
  console.log(`search ${kind}: ${found[kind].length} results; more=${found.more}`);
}

const rank = await plugin.browse({ page: "rank" });
assert.ok(rank.sections.some((section) => section.shows.length), "rank board is empty");
console.log(`rank: ${rank.sections.map((s) => `${s.title}=${s.shows.length}`).join(", ")}`);
const categories = await plugin.browse({ page: "categories" });
assert.ok(categories.sections[0].links.length > 1, "category links missing");
const firstCategory = categories.sections[0].links[1].page;
const category = await plugin.browse({ page: firstCategory });
assert.ok(category.sections[0].shows.length, "category albums empty");
console.log(`categories: ${categories.sections[0].links.length} links; ${firstCategory} -> ${category.sections[0].shows.length} albums`);

const first = await plugin.show({ id: "43290505", offset: 0 });
const second = await plugin.show({ id: "43290505", offset: first.episodes.length });
assert.ok(first.episodes.length && second.episodes.length, "album pagination failed");
assert.equal(new Set([...first.episodes, ...second.episodes].map((track) => track.id)).size, first.episodes.length + second.episodes.length);
console.log(`show: ${first.show.name}; pages=${first.episodes.length}+${second.episodes.length}`);

const resolved = await plugin.search({ query: "https://www.ximalaya.com/sound/351009997", kind: "tracks" });
assert.equal(resolved.tracks[0].id, "351009997");
const audio = await plugin.stream({ id: "351009997", quality: "high" });
assert.ok(["mp3", "m4a", "aac"].includes(audio.type), `unexpected audio type ${audio.type}`);
assert.ok(audio.url.startsWith("https://") && audio.url.includes(".xmcdn.com/"), "audio URL not on the CDN whitelist");
const response = await fetch(audio.url, {
  headers: { ...audio.headers, Range: "bytes=0-31" },
  redirect: "error",
  signal: AbortSignal.timeout(10000),
});
assert.equal(response.status, 206, "CDN did not answer with 206 Partial Content");
const reader = response.body.getReader();
const chunk = await reader.read();
await reader.cancel();
assert.ok(chunk.value?.byteLength > 0, "CDN returned no bytes");
console.log(`stream: type=${audio.type}; HTTP ${response.status}; ${response.headers.get("content-range")}`);

// The host may be set to lossless globally; the web player rarely offers FLAC.
const lossless = await plugin.stream({ id: "351009997", quality: "lossless" });
assert.ok(["mp3", "m4a", "aac", "flac"].includes(lossless.type));
assert.ok(lossless.quality !== "lossless" || lossless.type === "flac", "fallback must not pretend to be lossless");
console.log(`lossless fallback: quality=${lossless.quality}; type=${lossless.type}; fallbackFrom=${lossless.fallbackFrom || "none"}`);

await plugin.played({ id: first.episodes[0].id });
const local = await plugin.browse({ page: "local-recent" });
assert.equal(local.sections[0].tracks[0].id, first.episodes[0].id);
console.log(`local-recent: ${local.sections[0].tracks.length} records`);
console.log("Public smoke passed. Login, subscriptions, purchases and paid playback still require an in-app test.");
