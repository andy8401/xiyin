// Package facade is the gomobile-bindable bridge between the Go music core
// and the Kotlin/Compose car head-unit UI.
//
// gomobile constraints honored here:
//   - no map or func types in exported signatures: dictionaries cross JNI as
//     JSON strings, and every callback is a Go interface implemented by Kotlin.
//   - only exported types/methods are bound.
//
// The real plugin runtime (moejs, host API, permissions, storage) is reused
// unchanged from mygo-music/internal/plugin. This package only wires it up and
// serializes the music data model to JSON.
package facade

import (
	"encoding/json"
	"fmt"
	"sync"

	"mygo-music/internal/plugin"
)

// HostBridge is implemented by Kotlin to provide platform abilities that the
// desktop app gets from mygo.Shell / Dialog / WebView.
type HostBridge interface {
	OpenURL(url string) error
	WebSignInJSON(reqJSON string) (string, error)
	Toast(msg string)
}

// MediaOutput is implemented by Kotlin (Media3/ExoPlayer) for playback.
// The Go core hands it a stream URL plus headers instead of decoding audio
// itself, which also lets cnradio's HLS live streams play without the
// Python/ffmpeg bridge the desktop build needs.
type MediaOutput interface {
	Load(url, headersJSON string) error
	Play()
	Pause()
	SeekMs(ms int64)
	SetVolume(v float64) // 0..1
}

// Listener receives asynchronous events from Go. Kotlin implements it.
type Listener interface {
	OnChangedJSON(stateJSON string)
	OnError(source, msg string)
}

const appVersion = "0.1.25" // must be >= ximalaya-web's minAppVersion 0.1.20

type bridge struct {
	mu       sync.Mutex
	dataDir  string
	plugins  []*plugin.Plugin // loaded plugins, in discovery order
	host     HostBridge
	media    MediaOutput
	listener Listener

	// The queue is kept as raw JSON objects so no non-bindable music.Track
	// type appears in an exported signature.
	queueJSON      string
	queuePluginID  string
	queueStationID string // non-empty while a live station feeds the queue
	queueAt        int
	playing        bool
	volume         float64

	// signingIn marks the plugins with a sign-in in flight, so a second tap on
	// 登录 cannot start a second prompt over the first one.
	signingIn map[string]bool
}

var b = &bridge{volume: 1}

// SetHostBridge installs the platform ability provider. Call before Init.
func SetHostBridge(h HostBridge) { b.host = h }

// SetMediaOutput installs the Media3-backed player. Call before Init.
func SetMediaOutput(o MediaOutput) { b.media = o }

// SetListener installs the event sink. Call before Init.
func SetListener(l Listener) { b.listener = l }

func notify(state map[string]any) {
	if b.listener != nil {
		b.listener.OnChangedJSON(mustJSON(state))
	}
}

func toast(msg string) {
	if b.listener != nil {
		notify(map[string]any{"toast": msg})
	} else if b.host != nil {
		b.host.Toast(msg)
	}
}

func mustJSON(v any) string {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf(`{"error":%q}`, err.Error())
	}
	return string(data)
}

// Version returns the app version reported to plugins.
func Version() string { return appVersion }
