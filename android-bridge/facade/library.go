package facade

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"sync"

	"mygo-music/internal/local"
	"mygo-music/internal/music"
)

// The local library reuses mygo-music/internal/local unchanged. That source
// only depends on mygo/audio decoders (pure Go), not on the desktop GUI, and
// persists its own library.json under the injected directory.
type libraryState struct {
	mu     sync.Mutex
	source *local.Source
	dir    string
}

var lib = &libraryState{}

// initLibrary creates the local source under dataDir/local and wires its change
// notification to the Listener. Called from Init.
func initLibrary(dataDir string) {
	lib.mu.Lock()
	old := lib.source
	dir := filepath.Join(dataDir, "local")
	src := local.New(dir)
	src.OnChange(func() {
		notify(map[string]any{"event": "library"})
	})
	lib.source = src
	lib.dir = dir
	lib.mu.Unlock()
	if old != nil {
		// old source has no Close; goroutines finish on their own.
	}
}

// AddLocalFolders adds folders to the local library scan set.
func AddLocalFolders(foldersJSON string) error {
	var folders []string
	if err := json.Unmarshal([]byte(foldersJSON), &folders); err != nil {
		return fmt.Errorf("bad folders JSON: %w", err)
	}
	lib.mu.Lock()
	src := lib.source
	lib.mu.Unlock()
	if src == nil {
		return fmt.Errorf("library not initialized")
	}
	src.AddFolders(folders...)
	return nil
}

// LocalFoldersJSON returns the folders currently scanned as a JSON array.
func LocalFoldersJSON() (string, error) {
	lib.mu.Lock()
	src := lib.source
	lib.mu.Unlock()
	if src == nil {
		return "", fmt.Errorf("library not initialized")
	}
	folders := src.Folders()
	if folders == nil {
		// Kotlin/JSON consumers expect an array even when empty.
		folders = []string{}
	}
	return jsonMarshal(folders)
}

// RescanLibrary starts a background rescan of the local folders. Completion is
// signalled through Listener.OnChangedJSON with event="library".
func RescanLibrary() error {
	lib.mu.Lock()
	src := lib.source
	lib.mu.Unlock()
	if src == nil {
		return fmt.Errorf("library not initialized")
	}
	src.Rescan()
	return nil
}

// LocalTracksJSON returns the local library's Home browse data as JSON.
// It is a convenience for the car UI's "local music" home page.
func LocalTracksJSON() (string, error) {
	lib.mu.Lock()
	src := lib.source
	lib.mu.Unlock()
	if src == nil {
		return "", fmt.Errorf("library not initialized")
	}
	home, err := src.Home()
	if err != nil {
		return "", err
	}
	return jsonMarshal(home)
}

// LocalSearchJSON searches the local library (pure, no network).
func LocalSearchJSON(query string, offset int) (string, error) {
	lib.mu.Lock()
	src := lib.source
	lib.mu.Unlock()
	if src == nil {
		return "", fmt.Errorf("library not initialized")
	}
	res, err := src.Search(query, music.SearchTracks, offset)
	if err != nil {
		return "", err
	}
	return jsonMarshal(res)
}

