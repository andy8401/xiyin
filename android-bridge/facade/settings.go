package facade

import (
	"fmt"

	"mygo-music/internal/music"
)

// SettingsJSON returns a plugin's declared settings plus their current values
// as JSON: [{"key":..,"label":..,"help":..,"value":..,"options":[..]}, ...].
func SettingsJSON(pluginID string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, err := pluginByID(pluginID)
	if err != nil {
		return "", err
	}
	info := p.Info()
	type settingView struct {
		music.Setting
		Value string `json:"value"`
	}
	views := make([]settingView, 0, len(info.Settings))
	for _, s := range info.Settings {
		views = append(views, settingView{Setting: s, Value: p.Setting(s.Key)})
	}
	return jsonMarshal(views)
}

// SetSettingJSON updates a plugin setting. value is passed as a plain string;
// the plugin interprets it (e.g. "high"/"low", a command name).
func SetSettingJSON(pluginID, key, value string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, err := pluginByID(pluginID)
	if err != nil {
		return err
	}
	if err := p.SetSetting(key, value); err != nil {
		return fmt.Errorf("set %s.%s: %w", pluginID, key, err)
	}
	notify(map[string]any{"event": "setting", "plugin": pluginID, "key": key, "value": value})
	return nil
}

// HasCapability reports whether a plugin exports a named function (e.g.
// "stream", "search", "signIn"). Kotlin uses it to hide unsupported controls.
func HasCapability(pluginID, what string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, err := pluginByID(pluginID)
	if err != nil {
		return false
	}
	return p.Can(what)
}
