package facade

import (
	"fmt"
)

// RefreshContent re-fetches every cached content entry in the background and
// replaces it atomically. Kotlin calls it once at startup (after Init), so by
// the time the user opens a page the cache is usually already fresh. A page
// opened while its refresh is in flight still shows the cache immediately and
// receives a contentUpdated event when the new bytes land.
//
// Returns immediately: the work runs in a single goroutine that processes
// entries sequentially, so one plugin is never asked for two pages at once.
// Failures are silent and leave the previous cached bytes intact. Refreshing
// an entry rewrites it in place (it does not add a second copy), and the write
// also applies the cache's LRU bounds.
func RefreshContent() (string, error) {
	if b.dataDir == "" {
		return "", fmt.Errorf("bridge not initialized")
	}
	go refreshContentLoop()
	return `{"started":true}`, nil
}

func refreshContentLoop() {
	b.mu.Lock()
	ids := make([]string, 0, len(b.plugins))
	for _, p := range b.plugins {
		ids = append(ids, p.Info().ID)
	}
	b.mu.Unlock()

	for _, pluginID := range ids {
		for _, key := range cachedKeys(pluginID) {
			env, ok := readEnvelope(pluginID, key)
			if !ok {
				continue
			}
			data, err := refetch(pluginID, env)
			if err != nil || data == "" {
				continue // keep the old cache; it is still valid
			}
			if err := writeEnvelope(pluginID, key, env.Kind, env.Page, env.Detail, env.ID, env.Offset, data); err != nil {
				continue
			}
			// Tell the UI a specific entry changed. It decides whether the
			// user is still looking at that key before re-reading it.
			notify(map[string]any{
				"event":    "contentUpdated",
				"pluginID": pluginID,
				"kind":     env.Kind,
				"page":     env.Page,
				"detail":   env.Detail,
				"id":       env.ID,
				"offset":   env.Offset,
			})
		}
	}
}

// refetch rebuilds one cached entry from its envelope using the same code path
// as a first open, so refreshed bytes are identical in shape.
func refetch(pluginID string, env cacheEnvelope) (string, error) {
	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err // plugin uninstalled since the cache was written
	}

	switch env.Kind {
	case "browse":
		if env.Page == "home" || env.Page == "" {
			browse, err := p.Home()
			if err != nil {
				return "", err
			}
			return jsonMarshal(browse)
		}
		browse, err := p.Browse(env.Page)
		if err != nil {
			return "", err
		}
		return jsonMarshal(browse)
	case "detail":
		return fetchDetail(p, env.Detail, env.ID, env.Offset)
	}
	return "", fmt.Errorf("unknown cached kind %q", env.Kind)
}
