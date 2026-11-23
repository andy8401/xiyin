package facade

import (
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"strings"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

// Repeat modes. The order is the cycle order used by CycleRepeat (off -> all
// -> one -> off) and must stay stable; settings.json stores their names.
const (
	repeatOff int = iota
	repeatAll
	repeatOne
)

func repeatName(mode int) string {
	switch mode {
	case repeatOne:
		return "one"
	case repeatOff:
		return "off"
	default:
		return "all"
	}
}

func parseRepeat(name string) int {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "off":
		return repeatOff
	case "one":
		return repeatOne
	default:
		return repeatAll // also the default for an old or absent setting
	}
}

// currentIndex maps the play-order cursor to the raw queue index the UI uses.
// It returns -1 when there is no current track. Keeping the raw index on the
// wire means the queue pane highlights the same row in shuffled order.
func currentIndex() int {
	if b.queueAt < 0 || b.queueAt >= len(b.order) {
		return -1
	}
	return b.order[b.queueAt]
}

// resetOrder rebuilds the natural play order and puts start (a raw queue
// index) at the cursor. With shuffle on, the order is randomized with that
// same track at its head, so the row the listener tapped still plays first.
func resetOrder(n, start int) {
	b.order = make([]int, n)
	for i := range b.order {
		b.order[i] = i
	}
	if n == 0 {
		b.queueAt = -1
		return
	}
	if start < 0 || start >= n {
		start = 0
	}
	b.queueAt = start
	if b.shuffle {
		shuffleOrderLocked(start)
		b.queueAt = 0
	}
}

// shuffleOrderLocked randomizes order with first (a raw index) at its head.
func shuffleOrderLocked(first int) {
	rand.Shuffle(len(b.order), func(i, j int) { b.order[i], b.order[j] = b.order[j], b.order[i] })
	for i, v := range b.order {
		if v == first {
			b.order[0], b.order[i] = b.order[i], b.order[0]
			break
		}
	}
}

// applyShuffleLocked turns shuffle on/off without changing which track plays:
// turning it off restores natural order and points the cursor at the same row.
func applyShuffleLocked(on bool) {
	if b.shuffle == on {
		return
	}
	b.shuffle = on
	if len(b.order) == 0 {
		return
	}
	cur := currentIndex()
	if cur < 0 {
		return
	}
	if on {
		shuffleOrderLocked(cur)
		b.queueAt = 0
	} else {
		for i := range b.order {
			b.order[i] = i
		}
		b.queueAt = cur
	}
}

// PlayQueueJSON accepts a JSON array of music.Track objects plus the owning
// plugin id and a start index. It resolves only the selected track's stream
// (URL + headers) through its plugin and hands it to the Kotlin MediaOutput.
//
// The Go core deliberately stops here: decoding, caching and spectrum are left
// to Media3/ExoPlayer on Android. This also lets cnradio's HLS live streams play
// without the Python/ffmpeg bridge the desktop build needs.
func PlayQueueJSON(pluginID, tracksJSON string, start int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	var tracks []music.Track
	if err := json.Unmarshal([]byte(tracksJSON), &tracks); err != nil {
		return fmt.Errorf("bad tracks JSON: %w", err)
	}
	if start < 0 || start >= len(tracks) {
		return fmt.Errorf("start out of range: %d (queue %d)", start, len(tracks))
	}
	p, err := pluginByID(pluginID)
	if err != nil {
		return err
	}
	b.queueJSON = tracksJSON
	b.queuePluginID = pluginID
	b.queueStationID = ""
	resetOrder(len(tracks), start)
	return openCurrent(p)
}

// PlayStationJSON starts a live station. cnradio's Browse sections carry
// stations whose ids are live:<broadcast>, not Browse page ids; opening them as
// pages makes the plugin throw "no such page". StationNext is the correct
// desktop-equivalent entry point and returns the first playable track(s).
func PlayStationJSON(pluginID, stationID string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if strings.TrimSpace(stationID) == "" {
		return fmt.Errorf("empty station id")
	}
	p, err := pluginByID(pluginID)
	if err != nil {
		return err
	}
	return playStationLocked(p, stationID)
}

// playStationLocked asks a station for its next playable tracks and replaces
// the queue with them. Caller must hold b.mu. A station is a rolling window:
// its order is always natural and shuffle/repeat do not apply to it.
func playStationLocked(p *plugin.Plugin, stationID string) error {
	tracks, err := p.StationNext(stationID)
	if err != nil {
		if b.listener != nil {
			b.listener.OnError(p.Info().ID, err.Error())
		}
		return err
	}
	if len(tracks) == 0 {
		return fmt.Errorf("station returned no tracks: %s", stationID)
	}
	data, err := json.Marshal(tracks)
	if err != nil {
		return err
	}
	b.queueJSON = string(data)
	b.queuePluginID = p.Info().ID
	b.queueStationID = stationID
	b.order = make([]int, len(tracks))
	for i := range b.order {
		b.order[i] = i
	}
	b.queueAt = 0
	return openCurrent(p)
}

// openCurrent resolves and loads the current track into MediaOutput.
// Caller must hold b.mu.
func openCurrent(p *plugin.Plugin) error {
	var tracks []music.Track
	if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
		return err
	}
	idx := currentIndex()
	if idx < 0 || idx >= len(tracks) {
		return fmt.Errorf("queue position out of range: %d", b.queueAt)
	}
	t := tracks[idx]
	st, err := p.Stream(t.ID, "high")
	if err != nil {
		if b.listener != nil {
			b.listener.OnError(b.queuePluginID, err.Error())
		}
		return err
	}
	if st == nil || st.URL == "" {
		return fmt.Errorf("empty stream URL for %s", t.ID)
	}
	headers, err := json.Marshal(st.Headers)
	if err != nil {
		return err
	}
	if b.media == nil {
		return fmt.Errorf("no media output set")
	}
	if err := b.media.Load(st.URL, string(headers)); err != nil {
		return err
	}
	b.playing = true
	// Load() only prepares the item; starting playback is a separate call so a
	// fresh queue begins playing the way it does in the desktop app.
	b.media.Play()
	notify(map[string]any{
		"event":  "track",
		"index":  idx, // raw queue index, so the UI highlights the right row
		"track":  t,
		"stream": st,
	})
	return nil
}

// TogglePlay pauses or resumes playback and returns the new state.
func TogglePlay() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.media == nil {
		return b.playing
	}
	if b.playing {
		b.media.Pause()
		b.playing = false
	} else {
		b.media.Play()
		b.playing = true
	}
	notify(map[string]any{"event": "playing", "value": b.playing})
	return b.playing
}

// Next advances the queue and loads the next track. A manual next is a direct
// command from the listener, so it always advances and wraps at the end even
// in repeat-one mode; only a played-through track is governed by the mode.
// For a live station it asks the plugin for the next segment instead.
func Next() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	return nextLocked()
}

// nextLocked is Next without the lock so Ended can reuse it. Caller must hold
// b.mu.
func nextLocked() error {
	p, err := pluginByID(b.queuePluginID)
	if err != nil {
		return fmt.Errorf("no active queue: %w", err)
	}
	if b.queueStationID != "" {
		return playStationLocked(p, b.queueStationID)
	}
	if len(b.order) == 0 {
		return fmt.Errorf("empty queue")
	}
	b.queueAt = (b.queueAt + 1) % len(b.order)
	return openCurrent(p)
}

// PlayAt jumps to a queue position, given as a RAW queue index (what the UI
// shows), and loads it without rebuilding the queue. With shuffle on, the raw
// index is translated to its position in the play order.
func PlayAt(index int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.queueJSON == "" {
		return fmt.Errorf("no active queue")
	}
	p, err := pluginByID(b.queuePluginID)
	if err != nil {
		return err
	}
	// A live station's queue is a rolling window; picking a row by index would
	// land on a segment the station has already replaced.
	if b.queueStationID != "" {
		return fmt.Errorf("a live station only plays its current segment")
	}
	pos := -1
	for i, idx := range b.order {
		if idx == index {
			pos = i
			break
		}
	}
	if pos < 0 {
		return fmt.Errorf("queue position out of range: %d", index)
	}
	b.queueAt = pos
	return openCurrent(p)
}

// Prev goes back one track, wrapping to the last row from the first.
func Prev() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, err := pluginByID(b.queuePluginID)
	if err != nil {
		return fmt.Errorf("no active queue: %w", err)
	}
	if len(b.order) == 0 {
		return fmt.Errorf("empty queue")
	}
	// A live station's queue is a rolling window with no meaningful "previous".
	if b.queueStationID != "" {
		return fmt.Errorf("a live station only plays its current segment")
	}
	b.queueAt = (b.queueAt - 1 + len(b.order)) % len(b.order)
	return openCurrent(p)
}

// SetShuffle turns shuffle on/off and persists the choice. Toggling keeps the
// track that is playing: on reorders with it at the head, off restores natural
// order and points the cursor back at it.
func SetShuffle(on bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.shuffle == on {
		return nil
	}
	applyShuffleLocked(on)
	return persistPlaybackMode(b.shuffle, b.repeat)
}

// CycleRepeat advances off -> all -> one -> off and persists the choice. It
// returns the new mode name so the caller can show a toast if it wants.
func CycleRepeat() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.repeat = (b.repeat + 1) % 3
	if err := persistPlaybackMode(b.shuffle, b.repeat); err != nil {
		return "", err
	}
	return repeatName(b.repeat), nil
}

// SeekMs seeks to a position in milliseconds.
func SeekMs(ms int64) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.media == nil {
		return fmt.Errorf("no media output set")
	}
	b.media.SeekMs(ms)
	return nil
}

// SetVolume sets gain in 0..1.
func SetVolume(v float64) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if v < 0 || v > 1 {
		return fmt.Errorf("volume out of range: %v", v)
	}
	b.volume = v
	if b.media != nil {
		b.media.SetVolume(v)
	}
	return nil
}

// ReloadCurrent re-resolves the current track's stream and restarts playback.
// The Android client calls it after network recovery: signed stream URLs
// (cnradio HLS ?k=&t=) expire while offline, so retrying the old URL fails.
// Re-resolving through the plugin yields a fresh URL/headers. For on-demand
// tracks ms is the resume position in milliseconds (0 = from the start); for a
// live station ms is ignored and the plugin hands back its current segment.
// Caller is Kotlin on a background thread; the lock and the media calls are
// safe there (MediaOutputImpl posts them onto the player's main thread).
func ReloadCurrent(ms int64) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, err := pluginByID(b.queuePluginID)
	if err != nil {
		return fmt.Errorf("no active queue: %w", err)
	}
	if b.queueStationID != "" {
		// A live station is a rolling window; re-request its current segment.
		return playStationLocked(p, b.queueStationID)
	}
	if err := openCurrent(p); err != nil {
		return err
	}
	if ms > 0 && b.media != nil {
		b.media.SeekMs(ms)
	}
	return nil
}

// Ended is called by Kotlin when Media3 finishes a track. A live station asks
// for its next segment; a played-through on-demand queue follows the repeat
// mode: repeat-one restarts the same track, repeat-off stops on the last row,
// otherwise it advances. Manual Next deliberately bypasses all of this.
func Ended() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.queueStationID != "" {
		p, err := pluginByID(b.queuePluginID)
		if err != nil {
			return err
		}
		return playStationLocked(p, b.queueStationID)
	}
	if len(b.order) == 0 {
		return fmt.Errorf("empty queue")
	}
	if b.repeat == repeatOne {
		if b.media == nil {
			return fmt.Errorf("no media output set")
		}
		// Re-use the loaded item: re-resolving the stream would fetch a new
		// signed URL for no reason and can fail on a flaky network.
		b.media.SeekMs(0)
		b.media.Play()
		b.playing = true
		notify(map[string]any{"event": "playing", "value": true})
		return nil
	}
	if b.queueAt+1 >= len(b.order) && b.repeat == repeatOff {
		// Stop on the last track, keeping both the queue and the position so
		// pressing play again resumes it instead of starting over.
		b.playing = false
		if b.media != nil {
			b.media.Pause()
		}
		notify(map[string]any{"event": "playing", "value": false})
		return nil
	}
	return nextLocked()
}

// StateJSON returns a snapshot: playing, volume, queue position and length.
func StateJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return jsonMarshal(map[string]any{
		"playing": b.playing,
		"volume":  b.volume,
		"index":   currentIndex(),
		"length":  len(b.order),
		"shuffle": b.shuffle,
		"repeat":  repeatName(b.repeat),
	})
}

// QueueJSON returns the current queue as a JSON object with the full track
// array in natural order, the raw current index, the owning plugin and the
// playback-mode state, e.g. {"tracks":[...],"index":0,"pluginId":"...",
// "stationId":"","playing":true,"shuffle":false,"repeat":"all"}.
// The Compose UI uses it for the now-playing screen and the bottom player bar.
func QueueJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	var tracks []music.Track
	if b.queueJSON != "" {
		if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
			return "", err
		}
	}
	return jsonMarshal(map[string]any{
		"tracks":    tracks,
		"index":     currentIndex(),
		"pluginId":  b.queuePluginID,
		"stationId": b.queueStationID,
		"playing":   b.playing,
		"shuffle":   b.shuffle,
		"repeat":    repeatName(b.repeat),
	})
}

// ResolveStreamJSON is a convenience for Kotlin: given a plugin, track id and
// quality, return the music.Stream JSON (url + headers) without touching the
// queue. Useful for one-off playback and diagnosing stream issues.
func ResolveStreamJSON(pluginID, trackID, quality string) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(quality) == "" {
		quality = "high"
	}
	st, err := p.Stream(trackID, quality)
	if err != nil {
		return "", err
	}
	return jsonMarshal(st)
}

// SaveSession writes a playback snapshot from the live bridge. Kotlin passes
// the Media3 position because only it knows where the player actually is.
// Lock order is b.mu then st.mu, the same as Init and ResumeSession.
func SaveSession(positionMs int64) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.dataDir == "" {
		return fmt.Errorf("state store not initialized")
	}
	// Never overwrite a good snapshot with an empty queue: the driver may have
	// just opened the app to install a plugin before playing anything.
	if b.queueJSON == "" {
		return nil
	}
	s := Session{
		SourceID:  b.queuePluginID,
		QueueJSON: b.queueJSON,
		Index:     currentIndex(),
		Position:  positionMs,
		StationID: b.queueStationID,
		Playing:   b.playing,
	}
	if err := writeAtomic(sessionPath(), s); err != nil {
		return err
	}
	st.session = s
	st.hasSess = true
	return nil
}

// ResumeSession rebuilds the queue (or live station) from the saved snapshot
// and seeks to the saved position. Kotlin calls it once at boot with the
// autoResume setting: false still loads the track but leaves it paused, so a
// car never starts making noise by itself. Streams are re-resolved through the
// plugin, which matters for signed URLs that expired while the unit was off.
func ResumeSession(autoplay bool) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	st.mu.Lock()
	s := st.session
	has := st.hasSess
	st.mu.Unlock()
	if !has {
		return "", fmt.Errorf("no saved session")
	}
	if s.SourceID == "" {
		return "", fmt.Errorf("incomplete saved session")
	}
	p, err := pluginByID(s.SourceID)
	if err != nil {
		return "", err // the source was uninstalled since the snapshot
	}
	if s.StationID != "" {
		if err := playStationLocked(p, s.StationID); err != nil {
			return "", err
		}
	} else {
		var tracks []music.Track
		if err := json.Unmarshal([]byte(s.QueueJSON), &tracks); err != nil {
			return "", err
		}
		if len(tracks) == 0 {
			return "", fmt.Errorf("empty saved queue")
		}
		b.queueJSON = s.QueueJSON
		b.queuePluginID = s.SourceID
		b.queueStationID = ""
		resetOrder(len(tracks), s.Index) // honours the restored shuffle setting
		if err := openCurrent(p); err != nil {
			return "", err
		}
		if s.Position > 0 && b.media != nil {
			b.media.SeekMs(s.Position)
		}
	}
	if !autoplay && b.media != nil {
		b.media.Pause()
		b.playing = false
		notify(map[string]any{"event": "playing", "value": false})
	}
	return `{"restored":true}`, nil
}
