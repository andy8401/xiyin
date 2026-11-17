package facade

import (
	"encoding/json"
	"testing"
)

// lyricProbe is the JSON LyricsJSON emits, as Kotlin parses it.
type lyricProbe struct {
	Lines []struct {
		Start       int64  `json:"start"`
		End         int64  `json:"end"`
		Text        string `json:"text"`
		Translation string `json:"translation"`
	} `json:"lines"`
	Lang      string `json:"lang"`
	Supported bool   `json:"supported"`
	Reason    string `json:"reason"`
}

func loadLyrics(t *testing.T, pluginID, trackID string) lyricProbe {
	t.Helper()
	raw, err := LyricsJSON(pluginID, trackID)
	if err != nil {
		t.Fatalf("LyricsJSON(%s, %s): %v", pluginID, trackID, err)
	}
	var got lyricProbe
	if err := json.Unmarshal([]byte(raw), &got); err != nil {
		t.Fatalf("bad lyrics JSON: %v (%s)", err, raw)
	}
	return got
}

func initForLyrics(t *testing.T) {
	t.Helper()
	resetBridge(t)
	t.Cleanup(func() { resetBridge(t) })
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	SetListener(&testListener{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatalf("Init: %v", err)
	}
}

// demo answers t1 with raw LRC, which must come back as timed lines — the car
// UI never parses LRC itself.
func TestLyricsFromLRC(t *testing.T) {
	initForLyrics(t)

	got := loadLyrics(t, "demo", "t1")
	if !got.Supported {
		t.Fatalf("demo t1 reported unsupported: %s", got.Reason)
	}
	if len(got.Lines) != 2 {
		t.Fatalf("want 2 lines, got %d (%+v)", len(got.Lines), got.Lines)
	}
	if got.Lines[0].Start != 1000 || got.Lines[0].Text != "A plain line" {
		t.Errorf("first line = %+v, want start=1000 text=%q", got.Lines[0], "A plain line")
	}
	if got.Lines[1].Start != 4000 || got.Lines[1].Text != "And another" {
		t.Errorf("second line = %+v, want start=4000 text=%q", got.Lines[1], "And another")
	}
	// A short LRC line has no explicit end; FromSource gives it one so the UI
	// can animate through it.
	if got.Lines[0].End <= got.Lines[0].Start {
		t.Errorf("first line has no end: %+v", got.Lines[0])
	}
}

// demo answers every other track with timed Lines plus word timings; the text
// is left out there, so it has to be joined from the words.
func TestLyricsFromTimedLines(t *testing.T) {
	initForLyrics(t)

	got := loadLyrics(t, "demo", "t2")
	if !got.Supported {
		t.Fatalf("demo t2 reported unsupported: %s", got.Reason)
	}
	if len(got.Lines) == 0 {
		t.Fatal("demo t2 returned no lines")
	}
	first := got.Lines[0]
	if first.Start != 1000 || first.End != 3000 {
		t.Errorf("first line timing = %d..%d, want 1000..3000", first.Start, first.End)
	}
	if first.Text != "Word by word" {
		t.Errorf("first line text = %q, want %q", first.Text, "Word by word")
	}
	if first.Translation == "" {
		t.Error("first line lost its translation")
	}
}

// A source with no lyrics export must answer normally, not as an error: the
// player keeps working and the UI shows the reason where the words would be.
func TestLyricsUnsupportedSource(t *testing.T) {
	initForLyrics(t)

	got := loadLyrics(t, "cnradio", "live:1")
	if got.Supported {
		t.Errorf("cnradio should not report lyrics support: %+v", got)
	}
	if got.Reason == "" {
		t.Error("unsupported source came back without a reason")
	}
	if len(got.Lines) != 0 {
		t.Errorf("unsupported source returned lines: %+v", got.Lines)
	}
}

func TestLyricsRejectsBadInput(t *testing.T) {
	initForLyrics(t)

	if _, err := LyricsJSON("demo", "  "); err == nil {
		t.Error("empty track id should fail")
	}
	if _, err := LyricsJSON("nope", "t1"); err == nil {
		t.Error("unknown plugin should fail")
	}
}
