package facade

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Plugin content cache. The Go facade already owns the JSON bytes returned by
// a plugin's home/browse/detail call, so caching here (next to storage.json and
// settings.json) keeps the Kotlin side free of a second serialization layer.
//
// The cache is BOUNDED, not append-only:
//   - Re-fetching the same key overwrites that entry in place (it is updated,
//     never duplicated).
//   - Each plugin keeps at most maxCacheEntries entries and maxCacheBytes
//     total. A write that crosses either limit evicts the least-recently-used
//     entries, so opening many different shows / paging through episodes can
//     no longer grow the cache without limit.
//   - File mtime is the LRU clock. A cache hit touches the entry, so pages the
//     user actually reopens survive and old, unvisited pages go first. The
//     order survives an app restart because it lives in the filesystem.
//   - Uninstalling a plugin still removes its whole cache directory.

const cacheDirName = "content-cache"

// Limits are vars (not consts) so tests can lower them without writing tens of
// megabytes. Production code never changes them.
var (
	// maxCacheEntries caps cached pages per plugin. Home plus a handful of
	// named browse pages are few; detail keys (kind,id,offset) are the ones
	// that grow every time a different show is opened or "load more" is used.
	maxCacheEntries = 120

	// maxCacheBytes is a second bound for plugins whose JSON is large (a show
	// page with hundreds of episodes can be hundreds of KB on its own).
	maxCacheBytes int64 = 32 << 20 // 32 MiB
)

// cacheKey builds a stable, filesystem-safe name from the logical key parts.
// callers pass e.g. ("browse", page) or ("detail", kind, id, offset).
func cacheKey(parts ...string) string {
	h := sha256.Sum256([]byte(strings.Join(parts, "|")))
	return hex.EncodeToString(h[:])
}

func cacheFile(pluginID, key string) string {
	// A plugin id is validated by plugin.Load to be a safe name, but join via
	// filepath and keep the key hashed so a page id with '/' cannot escape.
	return filepath.Join(b.dataDir, cacheDirName, pluginID, key+".json")
}

// touchCache marks an entry as recently used. mtime is the LRU clock, so no
// sidecar index file is needed and the recency survives a process restart.
// Cache accounting is best-effort: a failing chtimes must not fail a read.
func touchCache(path string) {
	now := time.Now()
	_ = os.Chtimes(path, now, now)
}

// readCache returns the cached JSON for key, or "" when absent/unreadable.
// A stale/corrupt entry is treated as a miss (the caller then fetches fresh).
func readCache(pluginID, key string) string {
	path := cacheFile(pluginID, key)
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	touchCache(path)
	return string(data)
}

// writeCache stores JSON atomically: a crash mid-write leaves the old entry
// intact. Directories are created on first use. After a successful write the
// plugin's cache is pruned back within its bounds; the entry just written is
// protected so a successful fetch always leaves something usable on disk.
func writeCache(pluginID, key, content string) error {
	path := cacheFile(pluginID, key)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	part := path + ".part"
	if err := os.WriteFile(part, []byte(content), 0o600); err != nil {
		return err
	}
	if err := os.Rename(part, path); err != nil {
		return err
	}
	touchCache(path)
	pruneCache(pluginID, path)
	return nil
}

// cacheEntry is one cached file plus the metadata needed to order evictions.
type cacheEntry struct {
	path    string
	modTime time.Time
	size    int64
}

// pruneCache enforces the per-plugin entry-count and byte limits. Entries are
// removed oldest-access-first until both limits hold, except for `protect`
// (the entry just written) and the last remaining entry. Old .part leftovers
// from interrupted writes are swept here as well so they cannot accumulate.
//
// This is deliberately called on writes only, never on reads: eviction is a
// consequence of adding new content, and a plain cache hit must stay cheap.
func pruneCache(pluginID, protect string) {
	if pluginID == "" || b.dataDir == "" {
		return
	}
	dir := filepath.Join(b.dataDir, cacheDirName, pluginID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}

	items := make([]cacheEntry, 0, len(entries))
	var total int64
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(name, ".part") {
			// Leave a write that is currently in flight alone; only sweep
			// leftovers old enough to be from a crashed process.
			if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > time.Minute {
				_ = os.Remove(filepath.Join(dir, name))
			}
			continue
		}
		if !strings.HasSuffix(name, ".json") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		items = append(items, cacheEntry{
			path:    filepath.Join(dir, name),
			modTime: info.ModTime(),
			size:    info.Size(),
		})
		total += info.Size()
	}
	if len(items) <= maxCacheEntries && total <= maxCacheBytes {
		return
	}

	sort.Slice(items, func(i, j int) bool {
		if items[i].modTime.Equal(items[j].modTime) {
			return items[i].path < items[j].path
		}
		return items[i].modTime.Before(items[j].modTime)
	})

	count := len(items)
	for _, item := range items {
		if count <= 1 || (count <= maxCacheEntries && total <= maxCacheBytes) {
			break
		}
		if item.path == protect {
			continue
		}
		if err := os.Remove(item.path); err != nil {
			continue
		}
		count--
		total -= item.size
	}
}

// clearCache removes every cached entry (and any stray temp files) for a
// plugin. Called on uninstall.
func clearCache(pluginID string) {
	if pluginID == "" || b.dataDir == "" {
		return
	}
	_ = os.RemoveAll(filepath.Join(b.dataDir, cacheDirName, pluginID))
}

// cachedKeys lists the cache keys currently stored for a plugin. Used by the
// startup refresh to know which contents the user has actually opened, and by
// tests to assert that eviction happened.
func cachedKeys(pluginID string) []string {
	dir := filepath.Join(b.dataDir, cacheDirName, pluginID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	keys := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		keys = append(keys, strings.TrimSuffix(e.Name(), ".json"))
	}
	sort.Strings(keys)
	return keys
}

// ctxKind/ctxKey travel with a cached entry so a refresh can rebuild it: the
// file name is a hash, we also need the original arguments. We store a small
// envelope alongside each entry instead of relying on the key alone.
type cacheEnvelope struct {
	Kind   string          `json:"kind"`
	Page   string          `json:"page,omitempty"`
	Detail string          `json:"detail,omitempty"`
	ID     string          `json:"id,omitempty"`
	Offset int             `json:"offset,omitempty"`
	Data   json.RawMessage `json:"data"`
}

func writeEnvelope(pluginID, key, kind, page, detail, id string, offset int, data string) error {
	env := cacheEnvelope{
		Kind: kind, Page: page, Detail: detail, ID: id, Offset: offset,
		Data: json.RawMessage(data),
	}
	raw, err := json.Marshal(env)
	if err != nil {
		return err
	}
	return writeCache(pluginID, key, string(raw))
}

// readEnvelope parses a cached entry, returning the inner JSON and its rebuild
// parameters. Older/plain entries (no envelope) are treated as a miss. A hit
// refreshes mtime so frequently reopened pages are the last ones evicted.
func readEnvelope(pluginID, key string) (cacheEnvelope, bool) {
	var env cacheEnvelope
	path := cacheFile(pluginID, key)
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 || data[0] != '{' {
		return env, false
	}
	if err := json.Unmarshal(data, &env); err != nil || len(env.Data) == 0 {
		return env, false
	}
	touchCache(path)
	return env, true
}
