package facade

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"sort"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

// initHostWiring adapts HostBridge (a bindable Go interface implemented by
// Kotlin) to the func-based injection points the plugin package expects.
// Doing it here keeps func types strictly inside Go.
func initHostWiring() {
	plugin.SetAppVersion(appVersion)
	plugin.SetOpenURL(func(u string) error {
		if b.host == nil {
			return fmt.Errorf("no host bridge set")
		}
		return b.host.OpenURL(u)
	})
	plugin.SetWebSignIn(func(req plugin.WebSignIn) (string, error) {
		if b.host == nil {
			return "", fmt.Errorf("no host bridge set")
		}
		payload, err := json.Marshal(req)
		if err != nil {
			return "", err
		}
		// Kotlin shows the page (WebSignIn.URL, CookiesOf, Until, UserAgent)
		// and returns a Cookie header string.
		return b.host.WebSignInJSON(string(payload))
	})
}

// Init wires host callbacks and discovers plugins under dataDir/plugins.
// dataDir is the app-private directory provided by Kotlin (Context.filesDir).
// It returns the number of plugins loaded.
func Init(dataDir string) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if dataDir == "" {
		return 0, fmt.Errorf("empty dataDir")
	}
	b.dataDir = dataDir
	initHostWiring()

	// Stage C: persist settings/session and create the local library under the
	// injected dataDir. Both replace mygo.App.Path in the desktop build.
	if err := InitState(dataDir); err != nil {
		return 0, err
	}
	// Playback mode is a driver preference, so it survives a head-unit restart.
	b.shuffle, b.repeat = loadPlaybackMode()
	initLibrary(dataDir)

	dir := filepath.Join(dataDir, "plugins")
	data := filepath.Join(dataDir, "plugin-data")
	loaded := plugin.Discover(dir, data)
	sort.Slice(loaded, func(i, j int) bool {
		return loaded[i].Info().ID < loaded[j].Info().ID
	})
	b.plugins = loaded

	// Android has no host.spawn, so plugins whose default playback path needs
	// a helper process are switched to their in-process mode (cnradio: HLS
	// handed straight to Media3).
	defaulted := platformDefaults(loaded)

	notify(map[string]any{
		"event":            "ready",
		"plugins":          len(loaded),
		"dir":              dataDir,
		"platformDefaults": defaulted,
	})
	return len(loaded), nil
}

// LoadPlugin loads a single plugin from its own directory (used for local
// installs and for tests that point at an unpacked plugin folder).
func LoadPlugin(pluginDir, dataDir string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	initHostWiring()
	p, err := plugin.Load(pluginDir, dataDir)
	if err != nil {
		return err
	}
	for i, old := range b.plugins {
		if old.Info().ID == p.Info().ID {
			old.Close()
			b.plugins[i] = p
			return nil
		}
	}
	b.plugins = append(b.plugins, p)
	return nil
}

// pluginInfoView is music.Info plus the capabilities the UI needs before it
// offers a control. The embed is anonymous so the JSON stays flat: old clients
// simply ignore the two extra keys.
type pluginInfoView struct {
	music.Info
	SignIn  bool `json:"signIn"`
	Account bool `json:"account"`
}

// ListPluginsJSON returns the metadata of all loaded plugins as JSON:
// [{"id":..,"name":..,"version":..,"playback":..,"settings":..,"navigation":..,
// "signIn":..,"account":..}, ...]
//
// signIn/account come from the plugin's exports: a source that cannot sign in
// must not offer a sign-in button, and one that cannot answer account() must
// not pretend to know who is signed in.
func ListPluginsJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	infos := make([]pluginInfoView, 0, len(b.plugins))
	for _, p := range b.plugins {
		infos = append(infos, pluginInfoView{
			Info:    p.Info(),
			SignIn:  p.Can("signIn"),
			Account: p.Can("account"),
		})
	}
	return jsonMarshal(infos)
}

// pluginByID finds a loaded plugin by its manifest id.
func pluginByID(id string) (*plugin.Plugin, error) {
	for _, p := range b.plugins {
		if p.Info().ID == id {
			return p, nil
		}
	}
	return nil, fmt.Errorf("plugin not loaded: %s", id)
}

// BrowseJSON returns a plugin's Home page (page == "" or "home") or a named
// Browse page, serialized as a music.Browse JSON object.
//
// Content is cached on disk under a bounded LRU policy (see cache.go). A cached
// entry is returned immediately so re-opening a page never waits on the
// network; the startup refresh (RefreshContent) replaces it in the background
// and emits a contentUpdated event for the UI to pick up. Only the first open,
// before a cache exists, actually calls the plugin. Old, unvisited entries are
// evicted once the per-plugin entry/byte limits are reached.
func BrowseJSON(pluginID, page string) (string, error) {
	norm := page
	if norm == "" {
		norm = "home"
	}
	key := cacheKey("browse", norm)
	if env, ok := readEnvelope(pluginID, key); ok {
		return string(env.Data), nil
	}
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	var browse *music.Browse
	if norm == "home" {
		browse, err = p.Home()
	} else {
		browse, err = p.Browse(norm)
	}
	if err != nil {
		return "", err
	}
	data, err := jsonMarshal(browse)
	if err != nil {
		return "", err
	}
	// Cache is best-effort: a disk failure must not fail playback/UI.
	_ = writeEnvelope(pluginID, key, "browse", norm, "", "", 0, data)
	return data, nil
}

// SearchJSON runs a plugin search and returns a music.SearchResult as JSON.
func SearchJSON(pluginID, query string, kind string, offset int) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	res, err := p.Search(query, music.SearchKind(kind), offset)
	if err != nil {
		return "", err
	}
	return jsonMarshal(res)
}

func jsonMarshal(v any) (string, error) {
	data, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
