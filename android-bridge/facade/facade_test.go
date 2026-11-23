package facade

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// testHost is a no-op HostBridge. Plugin loading itself never calls out to the
// platform, but the wiring must be in place for moejs to install the host.
type testHost struct{}

func (testHost) OpenURL(string) error                 { return nil }
func (testHost) WebSignInJSON(string) (string, error) { return `{}`, nil }
func (testHost) Toast(string)                         {}

type testMedia struct {
	loaded string
	seekMs int64
	plays  int
	pauses int
}

func (m *testMedia) Load(url, headers string) error { m.loaded = url; return nil }
func (m *testMedia) Play()                          { m.plays++ }
func (m *testMedia) Pause()                         { m.pauses++ }
func (m *testMedia) SeekMs(ms int64)                { m.seekMs = ms }
func (m *testMedia) SetVolume(float64)              {}

type testListener struct{ changes, errors []string }

func (l *testListener) OnChangedJSON(s string)  { l.changes = append(l.changes, s) }
func (l *testListener) OnError(src, msg string) { l.errors = append(l.errors, src+": "+msg) }

func resetBridge(t *testing.T) {
	t.Helper()
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, p := range b.plugins {
		p.Close()
	}
	b.plugins = nil
	b.queueJSON = ""
	b.queuePluginID = ""
	b.queueStationID = ""
	b.order = nil
	b.queueAt = 0
	b.shuffle = false
	b.repeat = repeatAll
	b.playing = false
	b.signingIn = nil
}

func testDataDir(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs("../testdata")
	if err != nil {
		t.Fatal(err)
	}
	return p
}

// TestLoadAllPlugins proves the three real plugins compile and load through
// the facade's plugin.Load path (the same runtime the Android app will use).
func TestLoadAllPlugins(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})

	_, err := Init(testDataDir(t))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	got, err := ListPluginsJSON()
	if err != nil {
		t.Fatalf("ListPluginsJSON: %v", err)
	}
	var infos []struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Playback string `json:"playback"`
	}
	if err := json.Unmarshal([]byte(got), &infos); err != nil {
		t.Fatalf("bad ListPluginsJSON: %v (%s)", err, got)
	}

	want := map[string]bool{"demo": false, "cnradio": false, "ximalaya-web": false}
	for _, info := range infos {
		if _, ok := want[info.ID]; ok {
			want[info.ID] = true
		}
		t.Logf("loaded %s (%s) playback=%s", info.ID, info.Name, info.Playback)
	}
	for id, ok := range want {
		if !ok {
			t.Errorf("plugin %s not loaded", id)
		}
	}
}

// TestBrowseAndCapabilities exercises the pure-logic exports the plugins can
// answer without network: demo's home and each plugin's declared capabilities.
func TestBrowseAndCapabilities(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	// demo's home is pure fake data (no network).
	browse, err := BrowseJSON("demo", "")
	if err != nil {
		t.Fatalf("demo.BrowseJSON: %v", err)
	}
	if !strings.Contains(browse, "sections") && !strings.Contains(browse, "title") {
		t.Errorf("demo browse missing expected fields: %s", browse)
	}

	// Every plugin must report the stream capability it declares.
	for _, id := range []string{"demo", "cnradio", "ximalaya-web"} {
		if !HasCapability(id, "stream") {
			t.Errorf("%s does not report stream capability", id)
		}
	}
}

// TestSettingsAndState checks the JSON-only setting surface.
func TestSettingsAndState(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	s, err := SettingsJSON("demo")
	if err != nil {
		t.Fatalf("SettingsJSON: %v", err)
	}
	if !strings.Contains(s, "greeting") {
		t.Errorf("demo settings missing greeting: %s", s)
	}

	st, err := StateJSON()
	if err != nil {
		t.Fatalf("StateJSON: %v", err)
	}
	var state map[string]any
	if err := json.Unmarshal([]byte(st), &state); err != nil {
		t.Fatalf("bad StateJSON: %v", err)
	}
	if state["playing"] != false {
		t.Errorf("initial playing should be false, got %v", state["playing"])
	}
}

// TestBadPluginID verifies errors propagate as strings (bindable as error).
func TestBadPluginID(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}
	if _, err := BrowseJSON("nope", ""); err == nil {
		t.Error("expected error for unknown plugin")
	}
	if err := SetSettingJSON("nope", "k", "v"); err == nil {
		t.Error("expected error for unknown plugin setting")
	}
}

// TestPluginFilesPresent guards against testdata being stripped from a checkout.
func TestPluginFilesPresent(t *testing.T) {
	dir := testDataDir(t)
	for _, id := range []string{"demo", "cnradio", "ximalaya-web"} {
		manifest := filepath.Join(dir, "plugins", id, "plugin.json")
		if _, err := os.Stat(manifest); err != nil {
			t.Fatalf("missing testdata %s: %v", manifest, err)
		}
	}
}

// --- Stage C: state layer (settings / session / local library) -------------

func tempDataDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	return dir
}

func TestAppSettingsPersistence(t *testing.T) {
	dir := tempDataDir(t)
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	got, err := AppSettingsJSON()
	if err != nil {
		t.Fatal(err)
	}
	var s AppSettings
	if err := json.Unmarshal([]byte(got), &s); err != nil {
		t.Fatal(err)
	}
	if s.Quality != "high" || s.Volume != 0.8 {
		t.Fatalf("unexpected defaults: %+v", s)
	}

	patch := `{"quality":"low","volume":0.35,"normalize":false}`
	if err := SetSettingsJSON(patch); err != nil {
		t.Fatal(err)
	}

	// Reload from disk to prove persistence, not just the in-memory copy.
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	got, _ = AppSettingsJSON()
	if err := json.Unmarshal([]byte(got), &s); err != nil {
		t.Fatal(err)
	}
	if s.Quality != "low" || s.Volume != 0.35 || s.Normalize {
		t.Fatalf("settings not persisted: %+v", s)
	}
}

func TestSessionPersistence(t *testing.T) {
	dir := tempDataDir(t)
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := RestoreSessionJSON(); err == nil {
		t.Error("expected error before any session saved")
	}
	snap := `{"sourceId":"cnradio","queueJson":"[]","index":2,"position":12345}`
	if err := SaveSessionJSON(snap); err != nil {
		t.Fatal(err)
	}
	if err := InitState(dir); err != nil { // reload
		t.Fatal(err)
	}
	got, err := RestoreSessionJSON()
	if err != nil {
		t.Fatal(err)
	}
	var s Session
	if err := json.Unmarshal([]byte(got), &s); err != nil {
		t.Fatal(err)
	}
	if s.SourceID != "cnradio" || s.Index != 2 || s.Position != 12345 {
		t.Fatalf("session not persisted: %+v", s)
	}
	if err := ClearSession(); err != nil {
		t.Fatal(err)
	}
	if _, err := RestoreSessionJSON(); err == nil {
		t.Error("expected error after clear")
	}
}

func TestLocalLibraryReuse(t *testing.T) {
	dir := tempDataDir(t)
	initLibrary(dir)

	// Empty folder list must not crash; local.New reads/writes under dir/local.
	folders, err := LocalFoldersJSON()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(folders, "[") {
		t.Fatalf("bad folders JSON: %s", folders)
	}
	if err := AddLocalFolders(`["/tmp/music"]`); err != nil {
		t.Fatal(err)
	}
	folders, _ = LocalFoldersJSON()
	// local.Source applies filepath.Abs, so the returned form is platform-specific.
	// Assert that the list contains exactly one non-empty entry instead of a
	// literal path that only holds on Unix.
	var added []string
	if err := json.Unmarshal([]byte(folders), &added); err != nil {
		t.Fatalf("bad folders JSON after add: %s: %v", folders, err)
	}
	if len(added) != 1 || added[0] == "" {
		t.Fatalf("folder not added: %s", folders)
	}
	if err := RescanLibrary(); err != nil {
		t.Fatalf("RescanLibrary: %v", err)
	}
	if _, err := LocalTracksJSON(); err != nil {
		t.Fatalf("LocalTracksJSON: %v", err)
	}
	if _, err := LocalSearchJSON("x", 0); err != nil {
		t.Fatalf("LocalSearchJSON: %v", err)
	}
}

func TestBadSettingsAndSessionJSON(t *testing.T) {
	dir := tempDataDir(t)
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	if err := SetSettingsJSON("not json"); err == nil {
		t.Error("expected error for bad settings JSON")
	}
	if err := SaveSessionJSON("not json"); err == nil {
		t.Error("expected error for bad session JSON")
	}
}

// TestLocalInstallAndUninstall proves the stage E install path: a plugin folder
// from SAF/App Links is copied, checked and installed through plugin.Open +
// Pending.Install, then reflected in ListPluginsJSON and removable.
func TestLocalInstallAndUninstall(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})

	dir := t.TempDir()
	if _, err := Init(dir); err != nil {
		t.Fatalf("Init empty dataDir: %v", err)
	}

	// Nothing installed yet.
	list, err := ListPluginsJSON()
	if err != nil || strings.TrimSpace(list) != "[]" {
		t.Fatalf("initial list = %s, err = %v", list, err)
	}

	source := filepath.Join(testDataDir(t), "plugins", "demo")
	id, err := InstallPluginFromPath(source)
	if err != nil {
		t.Fatalf("InstallPluginFromPath: %v", err)
	}
	if id != "demo" {
		t.Fatalf("installed id = %s, want demo", id)
	}

	list, err = ListPluginsJSON()
	if err != nil || !strings.Contains(list, `"id":"demo"`) {
		t.Fatalf("list after install = %s, err = %v", list, err)
	}

	origins, err := PluginOriginsJSON()
	if err != nil {
		t.Fatalf("PluginOriginsJSON: %v", err)
	}
	var originList []struct {
		ID      string `json:"id"`
		Repo    string `json:"repo"`
		Version string `json:"version"`
		Dir     string `json:"dir"`
	}
	if err := json.Unmarshal([]byte(origins), &originList); err != nil {
		t.Fatalf("bad origins JSON: %v (%s)", err, origins)
	}
	if len(originList) != 1 || originList[0].ID != "demo" {
		t.Fatalf("origins after install = %s", origins)
	}
	if originList[0].Repo != "" {
		t.Fatalf("local install should have no repo, got %s", originList[0].Repo)
	}
	if originList[0].Version == "" {
		t.Fatalf("local install should report its manifest version: %s", origins)
	}

	// No repo-installed plugins, so the update check must be a safe empty list.
	updates, err := CheckPluginUpdatesJSON()
	if err != nil {
		t.Fatalf("CheckPluginUpdatesJSON: %v", err)
	}
	if strings.TrimSpace(updates) != "[]" {
		t.Fatalf("updates = %s, want []", updates)
	}

	if err := UninstallPlugin("demo"); err != nil {
		t.Fatalf("UninstallPlugin: %v", err)
	}
	list, err = ListPluginsJSON()
	if err != nil || strings.TrimSpace(list) != "[]" {
		t.Fatalf("list after uninstall = %s, err = %v", list, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "plugins", "demo")); !os.IsNotExist(err) {
		t.Fatalf("plugin folder still present after uninstall: %v", err)
	}
	if err := UninstallPlugin("demo"); err == nil {
		t.Error("second uninstall should fail")
	}
}

// TestInstallBadPath checks the error path: a missing source must not create a
// pending folder or change the loaded plugin set.
func TestInstallBadPath(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	dir := t.TempDir()
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := InstallPluginFromPath(filepath.Join(dir, "missing-plugin")); err == nil {
		t.Error("expected error for missing plugin path")
	}
	list, err := ListPluginsJSON()
	if err != nil || strings.TrimSpace(list) != "[]" {
		t.Fatalf("list changed after failed install: %s, err=%v", list, err)
	}
}

// TestQueueJSON verifies the now-playing data source returns the full track
// array and index after a queue is loaded.
func TestQueueJSON(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	empty, err := QueueJSON()
	if err != nil {
		t.Fatal(err)
	}
	var q struct {
		Tracks []json.RawMessage `json:"tracks"`
		Index  int               `json:"index"`
	}
	if err := json.Unmarshal([]byte(empty), &q); err != nil {
		t.Fatalf("bad empty queue JSON: %v (%s)", err, empty)
	}
	if len(q.Tracks) != 0 {
		t.Fatalf("empty queue has tracks: %s", empty)
	}

	browse, err := BrowseJSON("demo", "")
	if err != nil {
		t.Fatal(err)
	}
	track := firstTestTrack(browse)
	if track == nil {
		t.Fatal("demo has no track to queue")
	}
	queue := "[" + string(mustMarshal(track)) + "]"
	if err := PlayQueueJSON("demo", queue, 0); err != nil {
		t.Fatalf("PlayQueueJSON: %v", err)
	}
	got, err := QueueJSON()
	if err != nil {
		t.Fatal(err)
	}
	q = struct {
		Tracks []json.RawMessage `json:"tracks"`
		Index  int               `json:"index"`
	}{}
	if err := json.Unmarshal([]byte(got), &q); err != nil {
		t.Fatalf("bad queue JSON: %v (%s)", err, got)
	}
	if len(q.Tracks) != 1 || q.Index != 0 {
		t.Fatalf("queue = %s", got)
	}
}

// TestPlayStationRejectsBadInput covers the station entry point's guards. A
// live station id (live:<broadcast>) must never be sent to Browse(), which is
// what caused the Android "no such page" crash on cnradio station tiles.
func TestPlayStationRejectsBadInput(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}
	if err := PlayStationJSON("demo", ""); err == nil {
		t.Error("expected error for empty station id")
	}
	if err := PlayStationJSON("missing-plugin", "live:1"); err == nil {
		t.Error("expected error for unknown plugin")
	}
}

// TestPlatformDefaultsPickDirectBridge covers the Android-only default: with no
// host.spawn, cnradio's live streams must hand their HLS URL to the player
// instead of trying to start Python or ffmpeg. An explicit user choice must
// survive a restart.
func TestPlatformDefaultsPickDirectBridge(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)

	// The suite runs against testdata/plugin-data in place, so the second half
	// of this test must leave the setting unset for the next run.
	t.Cleanup(func() {
		resetBridge(t)
		SetHostBridge(testHost{})
		SetMediaOutput(&testMedia{})
		if _, err := Init(testDataDir(t)); err == nil {
			_ = SetSettingJSON("cnradio", "bridge", "")
		}
	})

	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}
	if got := cnradioBridge(t); got != "direct" {
		t.Fatalf("bridge defaulted to %q, want direct", got)
	}

	// A mode the user picked is kept across a re-init.
	if err := SetSettingJSON("cnradio", "bridge", "ffmpeg"); err != nil {
		t.Fatal(err)
	}
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}
	if got := cnradioBridge(t); got != "ffmpeg" {
		t.Errorf("explicit bridge setting was overwritten: %q", got)
	}
}

// cnradioBridge reads the current value of cnradio's "bridge" setting.
func cnradioBridge(t *testing.T) string {
	t.Helper()
	s, err := SettingsJSON("cnradio")
	if err != nil {
		t.Fatalf("SettingsJSON(cnradio): %v", err)
	}
	var views []struct {
		Key   string `json:"key"`
		Value string `json:"value"`
	}
	if err := json.Unmarshal([]byte(s), &views); err != nil {
		t.Fatalf("bad SettingsJSON: %v", err)
	}
	for _, v := range views {
		if v.Key == "bridge" {
			return v.Value
		}
	}
	t.Fatal("cnradio declares no bridge setting")
	return ""
}

func firstTestTrack(browse string) json.RawMessage {
	var obj struct {
		Sections []struct {
			Tracks []json.RawMessage `json:"tracks"`
		} `json:"sections"`
	}
	if err := json.Unmarshal([]byte(browse), &obj); err != nil {
		return nil
	}
	for _, s := range obj.Sections {
		if len(s.Tracks) > 0 {
			return s.Tracks[0]
		}
	}
	return nil
}

func mustMarshal(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}
