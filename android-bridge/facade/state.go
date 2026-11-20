package facade

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"mygo-music/internal/i18n"
)

// AppSettings is the car-unit subset of the desktop app's settings. It is a
// plain exported DTO (no map/func) so gomobile can bind it, and it is stored as
// JSON under the Kotlin-provided dataDir. Desktop-only fields (notch, stage,
// tray icon, smoke...) are intentionally left out.
type AppSettings struct {
	Language    string  `json:"language"`
	Source      string  `json:"source,omitempty"`
	Quality     string  `json:"quality"`
	Volume      float64 `json:"volume"`
	Normalize   bool    `json:"normalize"`
	Appearance  string  `json:"appearance"`
	CacheMB     int     `json:"cacheMB"`
	SearchLimit int     `json:"searchHistoryLimit,omitempty"`
	// Lyrics shows the lyrics pane in the now-playing screen. Default on: a
	// source without lyrics answers with its own "no lyrics" text.
	Lyrics bool `json:"lyrics"`
}

// syncLanguage speaks the setting in the i18n package the plugin host reads.
// "system" has no OS locale to resolve against on Android, so it falls back to
// Chinese for this zh-CN car product.
func syncLanguage(setting string) {
	preferred := []string{i18n.Chinese}
	i18n.SetLanguage(i18n.Resolve(setting, preferred))
}

func defaultAppSettings() AppSettings {
	return AppSettings{
		Language:   "system",
		Quality:    "high",
		Volume:     0.8,
		Normalize:  true,
		Appearance: "system",
		CacheMB:    2048,
		Lyrics:     true,
	}
}

// Session is a bindable playback snapshot. On Android the actual media
// position lives in Media3; Go keeps the queue, source and index so the UI can
// restore its list after a restart.
type Session struct {
	SourceID  string `json:"sourceId"`
	QueueJSON string `json:"queueJson"`
	Index     int    `json:"index"`
	Position  int64  `json:"position"` // milliseconds, last known from Kotlin
}

type stateStore struct {
	mu       sync.Mutex
	dataDir  string
	settings AppSettings
	session  Session
	hasSess  bool
}

var st = &stateStore{settings: defaultAppSettings()}

func settingsPath() string { return filepath.Join(st.dataDir, "settings.json") }
func sessionPath() string  { return filepath.Join(st.dataDir, "session.json") }

func writeAtomic(path string, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".part"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// InitState points the state store at dataDir and loads persisted settings and
// session. Call it from Init (it is called there automatically).
func InitState(dataDir string) error {
	if dataDir == "" {
		return fmt.Errorf("empty dataDir")
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	st.dataDir = dataDir
	st.settings = defaultAppSettings()
	if b, err := os.ReadFile(settingsPath()); err == nil {
		_ = json.Unmarshal(b, &st.settings)
	}
	// The desktop app calls i18n.SetLanguage in app.go once settings load. The
	// Android bridge has no app.go, so the language atom stayed at its default
	// English: plugin host.t("Cloud Radio") then missed locales/zh-CN.json and
	// the head unit showed English even in a Chinese car. Speak the saved
	// language before plugins are discovered. "system" resolves to Chinese here
	// — the car product is zh-CN and the bridge has no OS locale to ask.
	syncLanguage(st.settings.Language)
	st.hasSess = false
	if b, err := os.ReadFile(sessionPath()); err == nil {
		if json.Unmarshal(b, &st.session) == nil {
			st.hasSess = true
		}
	}
	return nil
}

// AppSettingsJSON returns the app settings as JSON.
func AppSettingsJSON() (string, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	return jsonMarshal(st.settings)
}

// SetSettingsJSON merges a JSON settings object into the current settings and
// persists it. Unknown fields are ignored; callers should read back with
// SettingsJSON to see the effective value.
func SetSettingsJSON(patch string) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	var p AppSettings
	if err := json.Unmarshal([]byte(patch), &p); err != nil {
		return fmt.Errorf("bad settings JSON: %w", err)
	}
	// Fields left at their zero value keep the current value, except booleans
	// and numbers that are meaningfully zero (normalize/cache off).
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(patch), &raw); err != nil {
		return err
	}
	merged := st.settings
	if _, ok := raw["language"]; ok { merged.Language = p.Language }
	if _, ok := raw["source"]; ok { merged.Source = p.Source }
	if _, ok := raw["quality"]; ok { merged.Quality = p.Quality }
	if _, ok := raw["volume"]; ok { merged.Volume = p.Volume }
	if _, ok := raw["normalize"]; ok { merged.Normalize = p.Normalize }
	if _, ok := raw["appearance"]; ok { merged.Appearance = p.Appearance }
	if _, ok := raw["cacheMB"]; ok { merged.CacheMB = p.CacheMB }
	if _, ok := raw["lyrics"]; ok { merged.Lyrics = p.Lyrics }
	if err := writeAtomic(settingsPath(), merged); err != nil {
		return err
	}
	st.settings = merged
	// Keep host.t in step with a language change at runtime, as the desktop
	// app's setLanguage does. Otherwise a plugin's locales stay in the old
	// language until the bridge restarts.
	syncLanguage(merged.Language)
	notify(map[string]any{"event": "settings", "value": merged})
	return nil
}

// SaveSessionJSON persists a playback snapshot. Kotlin should call this on
// pause/stop with the latest Media3 position.
func SaveSessionJSON(snapshot string) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	var s Session
	if err := json.Unmarshal([]byte(snapshot), &s); err != nil {
		return fmt.Errorf("bad session JSON: %w", err)
	}
	if err := writeAtomic(sessionPath(), s); err != nil {
		return err
	}
	st.session = s
	st.hasSess = true
	return nil
}

// RestoreSessionJSON returns the persisted session, or an error if none.
func RestoreSessionJSON() (string, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if !st.hasSess {
		return "", fmt.Errorf("no saved session")
	}
	return jsonMarshal(st.session)
}

// ClearSession removes the saved session.
func ClearSession() error {
	st.mu.Lock()
	defer st.mu.Unlock()
	if err := os.Remove(sessionPath()); err != nil && !os.IsNotExist(err) {
		return err
	}
	st.hasSess = false
	st.session = Session{}
	return nil
}
