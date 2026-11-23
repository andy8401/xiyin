package facade

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// setupSessionBridge gives each test a private dataDir but loads the real demo
// plugin, so SaveSession/ResumeSession run through the same path as the app.
func setupSessionBridge(t *testing.T, media *testMedia) string {
	t.Helper()
	dir := t.TempDir()
	SetHostBridge(testHost{})
	SetMediaOutput(media)
	SetListener(&testListener{})
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	source := filepath.Join(testDataDir(t), "plugins", "demo")
	if err := LoadPlugin(source, dir); err != nil {
		t.Fatalf("LoadPlugin: %v", err)
	}
	return dir
}

func readSession(t *testing.T, dir string) Session {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if err != nil {
		t.Fatal(err)
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestSaveSessionSnapshot(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayQueueJSON("demo", demoQueueJSON(), 2); err != nil {
		t.Fatal(err)
	}
	if err := SaveSession(45678); err != nil {
		t.Fatal(err)
	}
	s := readSession(t, dir)
	if s.SourceID != "demo" || s.Index != 2 || s.Position != 45678 || !s.Playing {
		t.Fatalf("bad snapshot: %+v", s)
	}
	if s.QueueJSON == "" || s.StationID != "" {
		t.Fatalf("snapshot missing queue or has a station: %+v", s)
	}
}

// An empty queue must not overwrite a good snapshot: the driver may open the
// app just to install a plugin before playing anything.
func TestSaveSessionSkipsEmptyQueue(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayQueueJSON("demo", demoQueueJSON(), 1); err != nil {
		t.Fatal(err)
	}
	if err := SaveSession(1000); err != nil {
		t.Fatal(err)
	}
	b.queueJSON = ""
	if err := SaveSession(9999); err != nil {
		t.Fatal(err)
	}
	if got := readSession(t, dir).Position; got != 1000 {
		t.Fatalf("empty queue overwrote snapshot position: %d", got)
	}
}

// autoResume=false is the default: the track and position are restored but
// playback stays paused, so a car never starts making noise by itself.
func TestResumeQueueStaysPausedByDefault(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayQueueJSON("demo", demoQueueJSON(), 1); err != nil {
		t.Fatal(err)
	}
	if err := SaveSession(12345); err != nil {
		t.Fatal(err)
	}
	// Reload just the state layer, as a boot would after Kotlin calls Init.
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	media.pauses = 0
	if _, err := ResumeSession(false); err != nil {
		t.Fatal(err)
	}
	if got := queueIndex(t); got != 1 {
		t.Fatalf("restored index = %d, want 1", got)
	}
	if media.seekMs != 12345 {
		t.Fatalf("restored position = %d, want 12345", media.seekMs)
	}
	if b.playing || media.pauses == 0 {
		t.Fatal("autoResume=false must leave the track paused")
	}
}

func TestResumeQueueWithAutoResume(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayQueueJSON("demo", demoQueueJSON(), 0); err != nil {
		t.Fatal(err)
	}
	if err := SaveSession(2222); err != nil {
		t.Fatal(err)
	}
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := ResumeSession(true); err != nil {
		t.Fatal(err)
	}
	if !b.playing {
		t.Fatal("autoResume=true should resume playback")
	}
	if media.seekMs != 2222 {
		t.Fatalf("restored position = %d, want 2222", media.seekMs)
	}
}

// A live station is a rolling window: restoring it must call StationNext
// instead of walking the stale rows saved in the snapshot.
func TestResumeLiveStation(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayStationJSON("demo", "st1"); err != nil {
		t.Fatal(err)
	}
	if err := SaveSession(0); err != nil {
		t.Fatal(err)
	}
	if got := readSession(t, dir).StationID; got != "st1" {
		t.Fatalf("saved station = %q, want st1", got)
	}
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := ResumeSession(true); err != nil {
		t.Fatal(err)
	}
	if b.queueStationID != "st1" || b.queueAt != 0 {
		t.Fatalf("station not restored: station=%q at=%d", b.queueStationID, b.queueAt)
	}
}

func TestResumeWithoutSessionFails(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	dir := t.TempDir()
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := ResumeSession(true); err == nil {
		t.Error("expected an error with no saved session")
	}
}

// The snapshot stores a raw queue index, so restoring under shuffle must land
// on the same track even though the play order was randomized again.
func TestResumeKeepsTrackUnderShuffle(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	dir := setupSessionBridge(t, media)

	if err := PlayQueueJSON("demo", demoQueueJSON(), 2); err != nil {
		t.Fatal(err)
	}
	if err := SetShuffle(true); err != nil {
		t.Fatal(err)
	}
	if queueIndex(t) != 2 {
		t.Fatal("shuffle changed the current track before saving")
	}
	if err := SaveSession(0); err != nil {
		t.Fatal(err)
	}
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	if !b.shuffle {
		t.Fatal("shuffle setting not restored")
	}
	if _, err := ResumeSession(false); err != nil {
		t.Fatal(err)
	}
	if got := queueIndex(t); got != 2 {
		t.Fatalf("restored index under shuffle = %d, want 2", got)
	}
}

func TestAutoResumeSettingPersists(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	dir := t.TempDir()
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	if err := SetSettingsJSON(`{"autoResume":true}`); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "settings.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"autoResume":true`) {
		t.Fatalf("autoResume not persisted: %s", data)
	}
	if err := InitState(dir); err != nil {
		t.Fatal(err)
	}
	got, err := AppSettingsJSON()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, `"autoResume":true`) {
		t.Fatalf("autoResume not loaded: %s", got)
	}
}
