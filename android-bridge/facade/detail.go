package facade

import (
	"errors"
	"fmt"
	"strconv"
	"strings"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

// Detail pages are one entry point rather than four exports: a plugin's show,
// album, artist and playlist pages all become a music.Browse, which is the shape
// the car UI already renders. Without this, tapping a card in ximalaya-web sent
// a bare numeric show id to Browse(), and the plugin answered
// "没有这个喜马拉雅页面" — its browse() only serves its own fixed pages, while
// the detail comes from the separate `show` export.
const (
	detailShow     = "show"
	detailAlbum    = "album"
	detailArtist   = "artist"
	detailPlaylist = "playlist"
)

// detailKinds is the single list of kinds OpenDetailJSON accepts, so a new
// Browse item kind cannot be added without teaching the detail router about it.
func detailKinds() []string {
	return []string{detailShow, detailAlbum, detailArtist, detailPlaylist}
}

// OpenDetailJSON opens a plugin's detail page by kind and id.
//
// kind is one of "show", "album", "artist", "playlist". offset pages through a
// show's episodes; it is ignored by the other kinds. The result is a
// music.Browse so Kotlin needs no per-kind parser:
//
//	{"title":..,"subtitle":..,"imageUrl":..,"sections":[{"title":..,"tracks":[..],"morePage":"show:<id>:<next>"}]}
//
// A section carrying more episodes advertises the next call in morePage, which
// is what the UI's "load more" row passes back through this same function.
func OpenDetailJSON(pluginID, kind, id string, offset int) (string, error) {
	if strings.TrimSpace(id) == "" {
		return "", fmt.Errorf("empty %s id", kind)
	}
	kind = strings.ToLower(strings.TrimSpace(kind))

	// Cached by the caller's (kind,id,offset) so re-opening the same detail page
	// is instant; RefreshContent replaces it in the background. The cache is
	// bounded and evicts least-recently-used entries (see cache.go).
	key := cacheKey("detail", kind, id, strconv.Itoa(offset))
	if env, ok := readEnvelope(pluginID, key); ok {
		return string(env.Data), nil
	}

	b.mu.Lock()
	p, err := pluginByID(pluginID)
	b.mu.Unlock()
	if err != nil {
		return "", err
	}
	data, err := fetchDetail(p, kind, id, offset)
	if err != nil {
		return "", err
	}
	_ = writeEnvelope(pluginID, key, "detail", "", kind, id, offset, data)
	return data, nil
}

// fetchDetail is the network/plugin path shared by OpenDetailJSON and its
// background refresh. It never touches the cache, so a refresh can rebuild the
// exact same bytes the first open produced.
func fetchDetail(p *plugin.Plugin, kind, id string, offset int) (string, error) {
	// The fallback below repairs a mismatched *known* kind (the UI says 专辑
	// while ximalaya-web exports show). An arbitrary kind is a caller bug: do
	// not silently open some other page for it.
	known := false
	for _, k := range detailKinds() {
		known = known || k == kind
	}
	if !known {
		return "", fmt.Errorf("unknown detail kind %q", kind)
	}

	// A source exports only what it can do, and the UI's label is not the
	// plugin's export name: ximalaya-web has show() but no album(), while every
	// card on its home page reads 专辑. Asking for the missing export answered
	// "not supported by this source" and the page never opened, so fall back to
	// whichever kind the source does answer.
	for _, k := range detailCandidates(kind) {
		if !p.Can(k) {
			continue
		}
		browse, err := detailBrowse(p, k, id, offset)
		if err != nil {
			if errors.Is(err, music.ErrUnsupported) {
				continue
			}
			return "", err
		}
		return jsonMarshal(browse)
	}

	// No detail export at all: a plain page may still serve the id (cnradio's
	// playback:<id> programme list is exactly that).
	if page, err := p.Browse(id); err == nil {
		return jsonMarshal(*page)
	}
	return "", fmt.Errorf("这个音源打不开这类页面（%s）", id)
}

// detailCandidates is the requested kind first, then the others the same id may
// reasonably answer. Show comes second because an album id in a source that
// exports only show() (ximalaya) is the same catalogue entry.
func detailCandidates(kind string) []string {
	out := []string{kind}
	for _, k := range detailKinds() {
		if k != kind {
			out = append(out, k)
		}
	}
	return out
}

// detailBrowse turns one plugin export into the Browse shape the car UI renders.
func detailBrowse(p *plugin.Plugin, kind, id string, offset int) (music.Browse, error) {
	switch kind {
	case detailShow:
		page, err := p.Show(id, offset)
		if err != nil {
			return music.Browse{}, err
		}
		return showPageBrowse(*page, offset), nil
	case detailAlbum:
		page, err := p.Album(id)
		if err != nil {
			return music.Browse{}, err
		}
		return albumPageBrowse(*page), nil
	case detailArtist:
		page, err := p.Artist(id)
		if err != nil {
			return music.Browse{}, err
		}
		return artistPageBrowse(*page), nil
	case detailPlaylist:
		page, err := p.Playlist(id)
		if err != nil {
			return music.Browse{}, err
		}
		return playlistBrowse(*page), nil
	default:
		return music.Browse{}, fmt.Errorf("unknown detail kind %q", kind)
	}
}

func showPageBrowse(page music.ShowPage, offset int) music.Browse {
	sec := music.Section{Title: page.Show.Name, Tracks: page.Episodes}
	if len(page.Episodes) == 0 {
		sec.Empty = "这一档还没有单集"
	}
	if page.More {
		sec.MorePage = fmt.Sprintf("%s:%s:%d", detailShow, page.Show.ID, offset+len(page.Episodes))
	}
	sections := []music.Section{sec}
	if page.Show.Description != "" {
		sections = append(sections, music.Section{
			Title: "简介",
			Links: []music.Link{{Title: page.Show.Description}},
		})
	}
	return music.Browse{
		Title:    page.Show.Name,
		Subtitle: showSubtitle(page.Show),
		ImageURL: page.Show.ImageURL,
		Sections: sections,
	}
}

func showSubtitle(s music.Show) string {
	parts := make([]string, 0, 2)
	if s.Publisher != "" {
		parts = append(parts, s.Publisher)
	}
	if s.EpisodeCount > 0 {
		parts = append(parts, fmt.Sprintf("%d 集", s.EpisodeCount))
	}
	return strings.Join(parts, " · ")
}

func albumPageBrowse(page music.AlbumPage) music.Browse {
	sec := music.Section{Title: page.Album.Name, Tracks: page.Tracks}
	if len(page.Tracks) == 0 {
		sec.Empty = "这张专辑没有可播放的曲目"
	}
	return music.Browse{
		Title:    page.Album.Name,
		Subtitle: albumSubtitle(page.Album),
		ImageURL: page.Album.ImageURL,
		Sections: []music.Section{sec},
	}
}

func albumSubtitle(a music.Album) string {
	parts := make([]string, 0, 2)
	if names := artistNames(a.Artists); names != "" {
		parts = append(parts, names)
	}
	if a.Released != "" {
		parts = append(parts, a.Released)
	}
	return strings.Join(parts, " · ")
}

func artistPageBrowse(page music.ArtistPage) music.Browse {
	var sections []music.Section
	if len(page.Tracks) > 0 {
		sections = append(sections, music.Section{Title: "热门单曲", Tracks: page.Tracks})
	}
	if len(page.Albums) > 0 {
		sections = append(sections, music.Section{Title: "专辑", Albums: page.Albums})
	}
	if len(sections) == 0 {
		sections = append(sections, music.Section{Title: page.Artist.Name, Empty: "这个艺人还没有作品"})
	}
	return music.Browse{
		Title:    page.Artist.Name,
		ImageURL: page.Artist.ImageURL,
		Sections: sections,
	}
}

func playlistBrowse(page music.Playlist) music.Browse {
	sec := music.Section{Title: page.Name, Tracks: page.Tracks}
	if len(page.Tracks) == 0 {
		sec.Empty = "这个歌单是空的"
	}
	return music.Browse{
		Title:       page.Name,
		Subtitle:    page.Owner,
		Description: page.Description,
		ImageURL:    page.ImageURL,
		Sections:    []music.Section{sec},
	}
}

func artistNames(in []music.Artist) string {
	names := make([]string, 0, len(in))
	for _, a := range in {
		if a.Name != "" {
			names = append(names, a.Name)
		}
	}
	return strings.Join(names, " / ")
}
