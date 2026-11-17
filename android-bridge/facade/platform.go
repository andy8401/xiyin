package facade

import (
	"sort"
	"strings"

	"mygo-music/internal/plugin"
)

// platformDefaults fixes plugin settings whose desktop default cannot work on
// Android and reports which plugins it touched.
//
// The plugin runtime has no host.spawn: an app cannot fork a helper process on
// Android. cnradio's live streams use such a helper ("bridge": Python or
// ffmpeg remuxes HLS into ADTS) and fail with "the plugin needs setting up"
// when it is unavailable. Its own `bridge=direct` mode hands the raw HLS URL
// to the player, and Media3 decodes HLS natively, so on Android "direct" is
// the correct mode rather than the debugging workaround the plugin describes.
//
// Only an unset value is defaulted; a mode the user picked explicitly wins.
// The value is written through Plugin.SetSetting so it persists in the
// plugin's settings.json and is visible in the app's Settings page.
func platformDefaults(plugins []*plugin.Plugin) []string {
	var applied []string
	for _, p := range plugins {
		if !needsDirectBridge(p) {
			continue
		}
		if err := p.SetSetting("bridge", "direct"); err != nil {
			continue
		}
		applied = append(applied, p.Info().ID)
	}
	sort.Strings(applied)
	return applied
}

// needsDirectBridge reports whether p declares a "bridge" setting that is
// still unset. The listener holds b.mu.
func needsDirectBridge(p *plugin.Plugin) bool {
	for _, s := range p.Info().Settings {
		if s.Key != "bridge" {
			continue
		}
		return strings.TrimSpace(p.Setting(s.Key)) == ""
	}
	return false
}
