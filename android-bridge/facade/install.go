package facade

import (
	"fmt"
	"path/filepath"

	"mygo-music/internal/plugin"
)

// Stage E: plugin installation, removal and update checks. The desktop build
// does all of this in internal/app/plugin_install.go with mygo.Dialog/Theme;
// here the Kotlin UI calls these bindable methods and receives JSON or errors.
//
// gomobile constraints: no map/func in signatures, so results cross JNI as
// JSON strings and errors as Go error values.

type pluginOrigin struct {
	ID      string `json:"id"`
	Repo    string `json:"repo,omitempty"`
	Version string `json:"version,omitempty"`
	Dir     string `json:"dir"`
}

type pluginUpdate struct {
	ID        string `json:"id"`
	Repo      string `json:"repo"`
	Current   string `json:"current"`
	Latest    string `json:"latest,omitempty"`
	Available bool   `json:"available"`
	Error     string `json:"error,omitempty"`
}

func pluginDir() string {
	return filepath.Join(b.dataDir, "plugins")
}

// InstallPluginFromPath installs a plugin from a local zip or folder. Kotlin
// passes a file it obtained through SAF or an App Link; the Go side copies,
// checks and installs it through the same code path the desktop build uses.
// Returns the installed plugin ID.
func InstallPluginFromPath(path string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dataDir == "" {
		return "", fmt.Errorf("bridge not initialized")
	}
	initHostWiring()

	pending, err := plugin.Open(path, pluginDir())
	if err != nil {
		return "", fmt.Errorf("open plugin: %w", err)
	}
	id := pending.Manifest.ID
	if err := pending.Install(pluginDir()); err != nil {
		pending.Discard()
		return "", fmt.Errorf("install plugin: %w", err)
	}
	if err := reloadPluginLocked(id); err != nil {
		return id, fmt.Errorf("installed %s but reload failed: %w", id, err)
	}
	notify(map[string]any{"event": "pluginInstalled", "id": id})
	return id, nil
}

// InstallPluginFromRepo installs or updates a plugin from a GitHub owner/repo.
// Network access happens inside plugin.Fetch; Kotlin must call this off the
// main thread. Returns the installed plugin ID.
func InstallPluginFromRepo(repo string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dataDir == "" {
		return "", fmt.Errorf("bridge not initialized")
	}
	initHostWiring()

	parsed, err := plugin.ParseRepo(repo)
	if err != nil {
		return "", fmt.Errorf("bad repo: %w", err)
	}
	pending, err := plugin.Fetch(parsed, pluginDir())
	if err != nil {
		return "", fmt.Errorf("fetch plugin: %w", err)
	}
	id := pending.Manifest.ID
	if err := pending.Install(pluginDir()); err != nil {
		pending.Discard()
		return "", fmt.Errorf("install plugin: %w", err)
	}
	if err := reloadPluginLocked(id); err != nil {
		return id, fmt.Errorf("installed %s but reload failed: %w", id, err)
	}
	notify(map[string]any{"event": "pluginInstalled", "id": id, "repo": parsed})
	return id, nil
}

// reloadPluginLocked closes any running instance of id, loads the installed
// copy and replaces it in b.plugins. Caller must hold b.mu.
func reloadPluginLocked(id string) error {
	loaded, err := plugin.Load(filepath.Join(pluginDir(), id), b.dataDir)
	if err != nil {
		return err
	}
	for i, old := range b.plugins {
		if old.Info().ID == id {
			old.Close()
			b.plugins[i] = loaded
			return nil
		}
	}
	b.plugins = append(b.plugins, loaded)
	return nil
}

// UninstallPlugin removes a plugin by ID and closes it first. Plugin data
// under plugin-data is intentionally kept so reinstalling restores state.
func UninstallPlugin(pluginID string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dataDir == "" {
		return fmt.Errorf("bridge not initialized")
	}
	for i, p := range b.plugins {
		if p.Info().ID != pluginID {
			continue
		}
		if err := p.Remove(nil); err != nil {
			return fmt.Errorf("remove plugin: %w", err)
		}
		b.plugins = append(b.plugins[:i], b.plugins[i+1:]...)
		if b.queuePluginID == pluginID {
			b.queueJSON = ""
			b.queuePluginID = ""
			b.queueAt = 0
			b.playing = false
		}
		notify(map[string]any{"event": "pluginRemoved", "id": pluginID})
		return nil
	}
	return fmt.Errorf("plugin not loaded: %s", pluginID)
}

// PluginOriginsJSON lists where each installed plugin came from:
// [{"id":..,"repo":..,"version":..,"dir":..}, ...]
func PluginOriginsJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	origins := make([]pluginOrigin, 0, len(b.plugins))
	for _, p := range b.plugins {
		info := p.Info()
		// A locally installed plugin has no origin.json; report its manifest
		// version so the UI can show it too.
		item := pluginOrigin{ID: info.ID, Version: info.Version, Dir: p.Dir()}
		if o := p.Origin(); o != nil {
			item.Repo = o.Repo
			if o.Version != "" {
				item.Version = o.Version
			}
		}
		origins = append(origins, item)
	}
	return jsonMarshal(origins)
}

// CheckPluginUpdatesJSON asks GitHub for the latest release of every plugin
// installed from a repository. Kotlin must call this off the main thread.
// A failing check is reported per plugin instead of failing the whole call.
func CheckPluginUpdatesJSON() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	updates := make([]pluginUpdate, 0, len(b.plugins))
	for _, p := range b.plugins {
		o := p.Origin()
		if o == nil || o.Repo == "" {
			continue
		}
		item := pluginUpdate{ID: p.Info().ID, Repo: o.Repo, Current: o.Version}
		latest, err := plugin.Latest(o.Repo)
		if err != nil {
			item.Error = err.Error()
		} else {
			item.Latest = latest.Version
			item.Available = plugin.IsNewer(latest.Version, o.Version)
		}
		updates = append(updates, item)
	}
	return jsonMarshal(updates)
}
