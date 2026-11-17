package facade

import (
	"fmt"
	"strings"

	"mygo-music/internal/lyrics"
)

// LyricsJSON returns a track's lyrics as timed lines the car UI can highlight.
//
// The plugin contract has two shapes for the same thing: raw LRC
// (music.Lyrics.LRC) and already-timed lines (music.Lyrics.Lines, which can
// time every word). Both are normalized here through internal/lyrics.FromSource
// so Kotlin only ever sees one array of {start,end,text,translation}.
//
// A source that does not ship lyrics (cnradio, most podcasts) is a normal
// answer, not a failure: it comes back as supported=false with a reason, so the
// UI can say so instead of showing an error where the words would be.
func LyricsJSON(pluginID, trackID string) (string, error) {
	if strings.TrimSpace(trackID) == "" {
		return "", fmt.Errorf("empty track id")
	}
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}

	if !p.Can("lyrics") {
		return jsonMarshal(lyricView{Supported: false, Reason: "这个音源没有提供歌词"})
	}
	raw, err := p.Lyrics(trackID)
	if err != nil {
		// The export exists but this track has none (or the source failed):
		// keep it out of the error path so the player keeps working.
		return jsonMarshal(lyricView{Supported: true, Reason: err.Error()})
	}
	lines := lyrics.FromSource(raw)
	view := lyricView{Supported: true, Lang: lyrics.Lang(lines)}
	if len(lines) == 0 {
		view.Reason = "这首歌没有歌词"
		return jsonMarshal(view)
	}
	view.Lines = make([]lyricLineView, 0, len(lines))
	for _, l := range lines {
		view.Lines = append(view.Lines, lyricLineView{
			Start:       l.At.Milliseconds(),
			End:         l.End.Milliseconds(),
			Text:        l.Text,
			Translation: l.Translation,
		})
	}
	return jsonMarshal(view)
}

type lyricView struct {
	Lines []lyricLineView `json:"lines,omitempty"`
	Lang  string          `json:"lang,omitempty"`
	// Supported is false when the source has no lyrics export at all.
	Supported bool   `json:"supported"`
	Reason    string `json:"reason,omitempty"`
}

type lyricLineView struct {
	Start       int64  `json:"start"`
	End         int64  `json:"end,omitempty"`
	Text        string `json:"text"`
	Translation string `json:"translation,omitempty"`
}
