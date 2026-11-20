package facade

import (
	"encoding/json"
	"testing"
)

// demoQueueJSON builds a three-track demo queue; every id resolves to the
// bundled tone.mp3, so no network is involved.
func demoQueueJSON() string {
	return `[{"id":"t1","name":"Song 1","source":"demo"},` +
		`{"id":"t2","name":"Song 2","source":"demo"},` +
		`{"id":"t3","name":"Song 3","source":"demo"}]`
}

func queueIndex(t *testing.T) int {
	t.Helper()
	raw, err := QueueJSON()
	if err != nil {
		t.Fatalf("QueueJSON: %v", err)
	}
	var q struct {
		Index int `json:"index"`
	}
	if err := json.Unmarshal([]byte(raw), &q); err != nil {
		t.Fatalf("bad queue JSON: %v (%s)", err, raw)
	}
	return q.Index
}

// Tapping a row in the queue pane must move playback to that row without
// rebuilding the queue (a fresh PlayQueueJSON would reset the list).
func TestPlayAtJumpsWithinQueue(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	media := &testMedia{}
	SetHostBridge(testHost{})
	SetMediaOutput(media)
	SetListener(&testListener{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatalf("Init: %v", err)
	}

	if err := PlayQueueJSON("demo", demoQueueJSON(), 0); err != nil {
		t.Fatalf("PlayQueueJSON: %v", err)
	}
	if queueIndex(t) != 0 {
		t.Fatalf("queue did not start at 0")
	}

	if err := PlayAt(2); err != nil {
		t.Fatalf("PlayAt(2): %v", err)
	}
	if got := queueIndex(t); got != 2 {
		t.Errorf("index after PlayAt(2) = %d, want 2", got)
	}
	if media.loaded == "" {
		t.Error("PlayAt did not load a stream")
	}

	// Jumping backwards must work too: it is a queue cursor, not a forward-
	// only iterator.
	if err := PlayAt(1); err != nil {
		t.Fatalf("PlayAt(1): %v", err)
	}
	if got := queueIndex(t); got != 1 {
		t.Errorf("index after PlayAt(1) = %d, want 1", got)
	}
}

// Queue is circular: Next past the last row wraps to the first and Prev before
// the first wraps to the last. This drives the car head unit's saved-station
// queue, where "next" at the final station must return to the first one.
func TestQueueWrapsAround(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	if err := PlayQueueJSON("demo", demoQueueJSON(), 0); err != nil {
		t.Fatalf("PlayQueueJSON: %v", err)
	}

	// Walk to the last row (index 2), then Next must wrap to 0.
	if err := PlayAt(2); err != nil {
		t.Fatalf("PlayAt(2): %v", err)
	}
	if err := Next(); err != nil {
		t.Fatalf("Next from last: %v", err)
	}
	if got := queueIndex(t); got != 0 {
		t.Errorf("Next from last = %d, want 0", got)
	}

	// From index 0, Prev must wrap to the last row (index 2).
	if err := Prev(); err != nil {
		t.Fatalf("Prev from first: %v", err)
	}
	if got := queueIndex(t); got != 2 {
		t.Errorf("Prev from first = %d, want 2", got)
	}
}

func TestPlayAtRejectsOutOfRange(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatalf("Init: %v", err)
	}

	if err := PlayAt(0); err == nil {
		t.Error("PlayAt on an empty queue should fail")
	}
	if err := PlayQueueJSON("demo", demoQueueJSON(), 0); err != nil {
		t.Fatalf("PlayQueueJSON: %v", err)
	}
	if err := PlayAt(3); err == nil {
		t.Error("PlayAt past the end should fail")
	}
	if err := PlayAt(-1); err == nil {
		t.Error("PlayAt with a negative index should fail")
	}
}
