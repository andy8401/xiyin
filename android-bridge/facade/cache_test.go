package facade

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// withCacheDataDir points the bridge at a private temp dir for the duration of
// a cache test. The facade is a package-level singleton and these tests do not
// call t.Parallel, so swapping dataDir here is safe.
func withCacheDataDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	b.mu.Lock()
	old := b.dataDir
	b.dataDir = dir
	b.mu.Unlock()
	t.Cleanup(func() {
		b.mu.Lock()
		b.dataDir = old
		b.mu.Unlock()
	})
	return dir
}

func putCache(t *testing.T, key, payload string) {
	t.Helper()
	if err := writeEnvelope("demo", key, "detail", "", "show", key, 0, payload); err != nil {
		t.Fatalf("writeEnvelope(%s): %v", key, err)
	}
}

// Re-fetching one page must replace its bytes, not create a second file. This
// is the "cache can be updated" half of the bounded-cache contract.
func TestCacheSameKeyUpdatesInPlace(t *testing.T) {
	withCacheDataDir(t)
	putCache(t, "same", `{"v":1}`)
	putCache(t, "same", `{"v":2}`)

	if got := len(cachedKeys("demo")); got != 1 {
		t.Fatalf("entries = %d, want 1 for a repeated key: %v", got, cachedKeys("demo"))
	}
	env, ok := readEnvelope("demo", "same")
	if !ok || !strings.Contains(string(env.Data), `"v":2`) {
		t.Fatalf("cache not updated in place: ok=%v data=%s", ok, env.Data)
	}
}

// Opening many different shows must not grow the cache forever: once the cap
// is hit, the least-recently-used entry is dropped. mtimes are assigned
// explicitly so the eviction order does not depend on wall-clock granularity.
func TestCacheEvictsLeastRecentlyUsed(t *testing.T) {
	withCacheDataDir(t)

	oldLimit := maxCacheEntries
	maxCacheEntries = 5
	t.Cleanup(func() { maxCacheEntries = oldLimit })

	for i := 0; i < 5; i++ {
		putCache(t, fmt.Sprintf("k%d", i), `{"v":0}`)
	}
	if got := len(cachedKeys("demo")); got != 5 {
		t.Fatalf("warmup entries = %d, want 5", got)
	}

	base := time.Now().Add(-time.Hour)
	for i := 0; i < 5; i++ {
		path := cacheFile("demo", fmt.Sprintf("k%d", i))
		at := base.Add(time.Duration(i) * time.Second)
		if err := os.Chtimes(path, at, at); err != nil {
			t.Fatal(err)
		}
	}

	// k0 is the oldest; reading it promotes it to the newest.
	if _, ok := readEnvelope("demo", "k0"); !ok {
		t.Fatal("k0 missing before eviction")
	}

	// A sixth page crosses the count cap. k1 (now oldest) must go, not k0.
	putCache(t, "k5", `{"v":1}`)
	if got := len(cachedKeys("demo")); got != 5 {
		t.Fatalf("entries after eviction = %d, want 5: %v", got, cachedKeys("demo"))
	}
	if _, ok := readEnvelope("demo", "k0"); !ok {
		t.Fatal("recently used k0 was evicted")
	}
	if _, ok := readEnvelope("demo", "k1"); ok {
		t.Fatal("oldest k1 should have been evicted")
	}
	if _, ok := readEnvelope("demo", "k5"); !ok {
		t.Fatal("newest k5 must be kept")
	}
}

// A plugin whose pages carry large JSON is bounded by bytes as well as count.
func TestCacheByteLimitEvicts(t *testing.T) {
	withCacheDataDir(t)

	oldBytes := maxCacheBytes
	maxCacheBytes = 120 // enough for one entry, not two
	t.Cleanup(func() { maxCacheBytes = oldBytes })

	putCache(t, "big1", `{"pad":"`+strings.Repeat("a", 30)+`"}`)
	putCache(t, "big2", `{"pad":"`+strings.Repeat("b", 30)+`"}`)

	if got := len(cachedKeys("demo")); got != 1 {
		t.Fatalf("entries = %d, want 1 under byte cap: %v", got, cachedKeys("demo"))
	}
	if _, ok := readEnvelope("demo", "big2"); !ok {
		t.Fatal("newest entry must survive the byte-cap eviction")
	}
}

// A crash between creating the .part file and renaming it must not leave a
// temp file behind forever. Only stale leftovers are swept, so an in-flight
// write is never disturbed.
func TestCacheSweepsStalePartFiles(t *testing.T) {
	dir := withCacheDataDir(t)
	putCache(t, "fresh", `{"v":1}`)

	part := filepath.Join(dir, cacheDirName, "demo", "orphan.json.part")
	if err := os.WriteFile(part, []byte("half write"), 0o600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-2 * time.Minute)
	if err := os.Chtimes(part, old, old); err != nil {
		t.Fatal(err)
	}

	putCache(t, "fresh2", `{"v":2}`) // any write runs the sweep
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Fatalf("stale .part was not swept: %v", err)
	}
}

func TestClearCacheRemovesAllEntries(t *testing.T) {
	withCacheDataDir(t)
	putCache(t, "a", `{}`)
	putCache(t, "b", `{}`)
	clearCache("demo")
	if got := cachedKeys("demo"); len(got) != 0 {
		t.Fatalf("cache not cleared: %v", got)
	}
}
