package facade

import (
	"encoding/json"
	"fmt"
	"strings"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

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
	b.queueAt = start
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
// the queue with them. Caller must hold b.mu.
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
	b.queueAt = 0
	return openCurrent(p)
}

// openCurrent resolves and loads the track at b.queueAt into MediaOutput.
// Caller must hold b.mu.
func openCurrent(p *plugin.Plugin) error {
	var tracks []music.Track
	if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
		return err
	}
	if b.queueAt < 0 || b.queueAt >= len(tracks) {
		return fmt.Errorf("queue position out of range: %d", b.queueAt)
	}
	t := tracks[b.queueAt]
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
		"index":  b.queueAt,
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

// Next advances the queue and loads the next track. For a live station it asks
// the plugin for the next segment instead of walking an exhausted queue.
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
	var tracks []music.Track
	if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
		return err
	}
	if len(tracks) == 0 {
		return fmt.Errorf("empty queue")
	}
	// Queue is circular: advancing past the last row wraps to the first.
	// nextLocked is also used by Ended(), so a played-through queue loops
	// back to the start instead of stopping on the final track.
	b.queueAt = (b.queueAt + 1) % len(tracks)
	return openCurrent(p)
}

// PlayAt jumps to a queue position and loads it. The car UI's queue pane taps a
// row and expects that track to start, without reshuffling the queue the way a
// fresh PlayQueueJSON call would.
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
	var tracks []music.Track
	if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
		return err
	}
	if index < 0 || index >= len(tracks) {
		return fmt.Errorf("queue position out of range: %d", index)
	}
	// A live station's queue is a rolling window; picking a row by index would
	// land on a segment the station has already replaced.
	if b.queueStationID != "" {
		return fmt.Errorf("a live station only plays its current segment")
	}
	b.queueAt = index
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
	var tracks []music.Track
	if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
		return err
	}
	if len(tracks) == 0 {
		return fmt.Errorf("empty queue")
	}
	// A live station's queue is a rolling window with no meaningful "previous".
	if b.queueStationID != "" {
		return fmt.Errorf("a live station only plays its current segment")
	}
	b.queueAt = (b.queueAt - 1 + len(tracks)) % len(tracks)
	return openCurrent(p)
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

// Ended is called by Kotlin when Media3 finishes a track; it advances the
// queue, or asks a live station for its next segment, mirroring the desktop
// player's OnEnd handler.
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
	return nextLocked()
}

// StateJSON returns a snapshot: playing, volume, queue position and length.
func StateJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	var tracks []music.Track
	if b.queueJSON != "" {
		if err := json.Unmarshal([]byte(b.queueJSON), &tracks); err != nil {
			return "", err
		}
	}
	return jsonMarshal(map[string]any{
		"playing": b.playing,
		"volume":  b.volume,
		"index":   b.queueAt,
		"length":  len(tracks),
	})
}

// QueueJSON returns the current queue as a JSON object with the full track
// array and the current index, e.g. {"tracks":[...],"index":0,"pluginId":"..."}.
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
		"index":     b.queueAt,
		"pluginId":  b.queuePluginID,
		"stationId": b.queueStationID,
		"playing":   b.playing,
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
