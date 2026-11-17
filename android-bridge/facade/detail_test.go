package facade

import (
	"encoding/json"
	"strings"
	"testing"
)

// detailView mirrors the subset of music.Browse the car UI reads, so the test
// asserts on the contract rather than on Go structs.
type detailView struct {
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Sections []struct {
		Title    string `json:"title"`
		MorePage string `json:"morePage"`
		Empty    string `json:"empty"`
		Tracks   []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"tracks"`
	} `json:"sections"`
}

// TestOpenDetailShow covers the bug that made every ximalaya-web card dead: the
// plugin's browse() has no detail pages, so a show id must go through the show
// export instead. demo/s1 is the offline equivalent.
func TestOpenDetailShow(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	raw, err := OpenDetailJSON("demo", "show", "s1", 0)
	if err != nil {
		t.Fatalf("OpenDetailJSON(show): %v", err)
	}
	var view detailView
	if err := json.Unmarshal([]byte(raw), &view); err != nil {
		t.Fatalf("bad detail JSON: %v\n%s", err, raw)
	}
	if view.Title != "The Show" {
		t.Errorf("title = %q, want The Show", view.Title)
	}
	if len(view.Sections) == 0 || len(view.Sections[0].Tracks) == 0 {
		t.Fatalf("show page has no episodes: %s", raw)
	}
	if view.Sections[0].Tracks[0].ID == "" {
		t.Errorf("episode without id: %s", raw)
	}
	// demo's getShow reports more=false, so no morePage must be advertised.
	if view.Sections[0].MorePage != "" {
		t.Errorf("morePage = %q, want empty when the plugin says more=false", view.Sections[0].MorePage)
	}
}

// TestOpenDetailAlbum checks a second kind maps into the same Browse shape.
func TestOpenDetailAlbum(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	raw, err := OpenDetailJSON("demo", "album", "al1", 0)
	if err != nil {
		t.Fatalf("OpenDetailJSON(album): %v", err)
	}
	var view detailView
	if err := json.Unmarshal([]byte(raw), &view); err != nil {
		t.Fatalf("bad detail JSON: %v\n%s", err, raw)
	}
	if view.Title != "First Album" {
		t.Errorf("title = %q, want First Album", view.Title)
	}
	if len(view.Sections) == 0 || len(view.Sections[0].Tracks) == 0 {
		t.Errorf("album page has no tracks: %s", raw)
	}
	if !strings.Contains(view.Subtitle, "The Examples") {
		t.Errorf("subtitle should name the artist, got %q", view.Subtitle)
	}
}

// TestOpenDetailRejectsBadInput keeps JNI-side errors predictable: Go throws
// across the bridge, which is what killed the app before the station fix.
func TestOpenDetailRejectsBadInput(t *testing.T) {
	resetBridge(t)
	defer resetBridge(t)
	SetHostBridge(testHost{})
	SetMediaOutput(&testMedia{})
	if _, err := Init(testDataDir(t)); err != nil {
		t.Fatal(err)
	}

	if _, err := OpenDetailJSON("demo", "show", "", 0); err == nil {
		t.Error("expected error for empty id")
	}
	if _, err := OpenDetailJSON("demo", "bogus", "s1", 0); err == nil {
		t.Error("expected error for unknown kind")
	}
	if _, err := OpenDetailJSON("nope", "show", "s1", 0); err == nil {
		t.Error("expected error for unknown plugin")
	}
}

// TestDetailKindsAreBrowsable guards the reachability rule: every kind a Browse
// item can carry must be openable through OpenDetailJSON.
func TestDetailKindsAreBrowsable(t *testing.T) {
	want := []string{"show", "album", "artist", "playlist"}
	got := map[string]bool{}
	for _, k := range detailKinds() {
		got[k] = true
	}
	for _, k := range want {
		if !got[k] {
			t.Errorf("detail kind %q missing", k)
		}
	}
}
