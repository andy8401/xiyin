package facade

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setupPlayModeTest(t *testing.T, q string, start int, media *testMedia) {
	t.Helper()
	SetHostBridge(testHost{})
	SetMediaOutput(media)
	SetListener(&testListener{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}
	if err := PlayQueueJSON("demo", q, start); err != nil {
		t.Fatalf("PlayQueueJSON: %v", err)
	}
}

func wantIndex(t *testing.T, want int) {
	t.Helper()
	if got := queueIndex(t); got != want {
		t.Fatalf("index = %d, want %d", got, want)
	}
}

// The default mode keeps the old behavior: playing through the last row wraps
// to the first.
func TestRepeatAllWrapsOnEnded(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 2, media)

	wantIndex(t, 2)
	if err := Ended(); err != nil {
		t.Fatal(err)
	}
	wantIndex(t, 0)
}

// repeat-off must stop on the last track but keep the queue and the cursor, so
// pressing play again resumes rather than restarting.
func TestRepeatOffStopsOnLastTrack(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 2, media)
	b.repeat = repeatOff

	if err := Ended(); err != nil {
		t.Fatal(err)
	}
	wantIndex(t, 2) // still on the last row
	raw, _ := QueueJSON()
	var state struct {
		Playing bool              `json:"playing"`
		Tracks  []json.RawMessage `json:"tracks"`
	}
	if err := json.Unmarshal([]byte(raw), &state); err != nil {
		t.Fatal(err)
	}
	if state.Playing {
		t.Fatalf("playing should be false after a played-through last track: %s", raw)
	}
	if len(state.Tracks) != 3 {
		t.Fatalf("queue was cleared: %s", raw)
	}
	if !TogglePlay() {
		t.Fatal("play after stop should resume on the same track")
	}
	wantIndex(t, 2)
}

// repeat-one restarts the same loaded item. It must not re-resolve the stream:
// signed URLs expire and a needless re-fetch can fail on a flaky connection.
func TestRepeatOneRestartsSameLoadedItem(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 1, media)
	b.repeat = repeatOne

	loaded := media.loaded
	if loaded == "" {
		t.Fatal("setup did not load a stream")
	}
	if err := Ended(); err != nil {
		t.Fatal(err)
	}
	wantIndex(t, 1)
	if media.loaded != loaded {
		t.Fatalf("repeat-one re-resolved the stream: %q -> %q", loaded, media.loaded)
	}
}

// A listener pressing "next" means next, even in repeat-one. The mode only
// governs what happens when a track plays to the end.
func TestManualNextIgnoresRepeatOne(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 1, media)
	b.repeat = repeatOne

	if err := Next(); err != nil {
		t.Fatal(err)
	}
	wantIndex(t, 2)
}

func TestShuffleKeepsCurrentTrackAndRestoresOrder(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 1, media)

	// In-process toggle only: these are behavior tests on the shared testdata
	// dir, so they must not persist a mode into testdata/settings.json.
	applyShuffleLocked(true)
	if !b.shuffle || len(b.order) != 3 || b.order[0] != 1 {
		t.Fatalf("shuffle did not put the current row first: order=%v shuffle=%v", b.order, b.shuffle)
	}
	seen := map[int]bool{}
	for _, idx := range b.order {
		if idx < 0 || idx >= 3 || seen[idx] {
			t.Fatalf("order is not a permutation: %v", b.order)
		}
		seen[idx] = true
	}
	wantIndex(t, 1) // still the same track for the listener

	applyShuffleLocked(false)
	if b.shuffle {
		t.Fatal("shuffle did not turn off")
	}
	if b.order[0] != 0 || b.order[1] != 1 || b.order[2] != 2 {
		t.Fatalf("natural order not restored: %v", b.order)
	}
	wantIndex(t, 1)
}

// The UI sends raw queue indices. With shuffle on, PlayAt has to translate
// them through the play order instead of treating them as cursor positions.
func TestPlayAtUsesRawIndexUnderShuffle(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	q := demoQueueJSON()
	media := &testMedia{}
	setupPlayModeTest(t, q, 0, media)

	applyShuffleLocked(true) // no persistence: testdata is shared
	if err := PlayAt(2); err != nil {
		t.Fatal(err)
	}
	wantIndex(t, 2)
	if b.order[b.queueAt] != 2 {
		t.Fatalf("cursor points at raw index %d, want 2", currentIndex())
	}
}

func TestCycleRepeatOrder(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	dir := t.TempDir()
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"one", "off", "all"} {
		got, err := CycleRepeat()
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Fatalf("CycleRepeat = %q, want %q", got, want)
		}
	}
}

// The mode is a driver preference: it must survive a head-unit restart via
// settings.json, and both JSON snapshots the UI reads must report it.
func TestPlaybackModePersistsAcrossInit(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	dir := t.TempDir()
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	if err := SetShuffle(true); err != nil {
		t.Fatal(err)
	}
	if _, err := CycleRepeat(); err != nil { // all -> one
		t.Fatal(err)
	}

	data, err := os.ReadFile(filepath.Join(dir, "settings.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"shuffle":true`) || !strings.Contains(string(data), `"repeat":"one"`) {
		t.Fatalf("mode not persisted: %s", data)
	}

	// Re-init points the state store at the same file, exactly like a restart.
	if _, err := Init(dir); err != nil {
		t.Fatal(err)
	}
	if !b.shuffle || b.repeat != repeatOne {
		t.Fatalf("mode not restored: shuffle=%v repeat=%v", b.shuffle, repeatName(b.repeat))
	}
	raw, err := QueueJSON()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(raw, `"shuffle":true`) || !strings.Contains(raw, `"repeat":"one"`) {
		t.Fatalf("QueueJSON missing mode: %s", raw)
	}
	raw, err = StateJSON()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(raw, `"shuffle":true`) || !strings.Contains(raw, `"repeat":"one"`) {
		t.Fatalf("StateJSON missing mode: %s", raw)
	}
}
