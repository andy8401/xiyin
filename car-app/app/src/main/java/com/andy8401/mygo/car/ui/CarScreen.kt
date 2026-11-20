package com.andy8401.mygo.car.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andy8401.mygo.car.BuildVersion
import com.andy8401.mygo.car.R
import com.andy8401.mygo.car.data.Browse
import com.andy8401.mygo.car.data.BrowseItem
import com.andy8401.mygo.car.data.FavoriteItem
import com.andy8401.mygo.car.data.NavItem
import com.andy8401.mygo.car.data.PlayerState
import com.andy8401.mygo.car.data.SourceInfo
import com.andy8401.mygo.car.data.Track
import com.andy8401.mygo.car.data.toFavorite
import com.andy8401.mygo.car.ui.CarColors.Background
import com.andy8401.mygo.car.ui.CarColors.Cyan
import com.andy8401.mygo.car.ui.CarColors.Surface
import com.andy8401.mygo.car.ui.CarColors.SurfaceHigh
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted
import com.andy8401.mygo.car.ui.MainViewModel.Page

/**
 * Landscape car layout locked to a centred 16:9 content surface.
 *
 * Every page renders inside the safe area inset by the system bars. The app
 * content stays 16:9 and centred, so on an ultra-wide head unit (Lynk & Co 900,
 * 32:10) it does NOT stretch or overflow the vehicle's bottom bar. The old
 * 32:9 maximize affordance has been removed from the driver UI.
 */
@Composable
fun CarScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            // Only inset the BOTTOM edge for the vehicle's own system bar.
            // navigationBarsPadding() insets left/right too on landscape builds
            // (gesture nav), which left an unwanted empty vertical strip beside
            // the rail on the Lynk & Co 900 panel.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    ) {
        val safeWidth = maxWidth
        val safeHeight = maxHeight
        // 16:9 by default; maximized only on HOME. Width is derived from the
        // usable HEIGHT and capped to the panel width so the content can never
        // exceed the screen (the old bug on ultra-wide panels).
        val isPlayingPage = ui.page == Page.HOME
        val wide = isPlayingPage && ui.maximized
        val contentWidth = if (wide) safeWidth else minOf(safeWidth, safeHeight * 16f / 9f)
        val contentHeight = if (wide) safeHeight else contentWidth * 9f / 16f

        // Full safe area: dark background tints the transparent system bars.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The 16:9 / maximized app surface, centred inside the safe area.
            Box(Modifier.width(contentWidth).height(contentHeight).background(Background)) {
                Row(Modifier.fillMaxSize()) {
                    LeftRail(ui, vm, contentHeight)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            ContentArea(ui, vm, Modifier.weight(1f))
                            PlayerBar(
                                player = ui.player,
                                lyricsEnabled = ui.settings.lyrics,
                                onOpen = { vm.openPlayer() },
                                onOpenQueue = { vm.openPlayer(MainViewModel.PlayerPane.QUEUE) },
                                favorite = ui.currentPlayable != null &&
                                    ui.favorites.any { it.key == ui.currentFavoriteKey },
                                onToggleFavorite = { vm.toggleCurrentFavorite() },
                                vm = vm,
                                availableHeight = contentHeight
                            )
                        }
                    }
                }
                if (ui.playerOpen) {
                    NowPlayingSheet(ui, vm)
                }
                // The plugin form and the uninstall prompt both outrank the player
                // sheet: they are short-lived and the user is mid-task. The sign-in
                // card sits on top of all of them.
                PluginSettingsSheet(ui, vm)
                ui.appUpdate?.let { AppUpdateDialog(it, vm) }
                UninstallDialog(ui, vm)
                SignInPromptSheet(ui, vm)
                if (ui.message.isNotBlank()) {
                    ToastBubble(ui.message, onDone = { vm.dismissMessage() })
                }
            }
        }
    }
}

/**
 * Brand mark: a simplified version of the launcher icon's voiceprint — two
 * concentric rings plus the solid sound source. Three rings blurred together
 * when shrunk to the small bottom-row mark, so the inner ring was removed.
 */
@Composable
fun VoiceprintMark(size: androidx.compose.ui.unit.Dp, color: Color = Cyan) {
    Canvas(modifier = Modifier.size(size)) {
        val c = Offset(size.toPx() / 2f, size.toPx() / 2f)
        val rOut = size.toPx() * 0.46f
        val stroke = (size.toPx() * 0.055f).coerceAtLeast(1.5f)
        // two rings, outer -> inner, fading in
        listOf(
            1.00f to 0.34f,
            0.64f to 0.70f
        ).forEach { (scale, alpha) ->
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = rOut * scale,
                center = c,
                style = Stroke(width = stroke)
            )
        }
        // solid sound source
        drawCircle(color = color, radius = rOut * 0.22f, center = c)
    }
}

/** One-line rail text: never wraps, scrolls only when the label overflows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailLabel(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.basicMarquee()
    )
}

/** Site favicon of a plugin. Bundled plugins ship a local copy so the rail
 *  still shows the right icon when the head unit is offline. */
@Composable
private fun PluginIcon(source: SourceInfo?, size: androidx.compose.ui.unit.Dp) {
    val res = when (source?.id) {
        "cnradio" -> R.drawable.plugin_cnradio
        "ximalaya-web" -> R.drawable.plugin_ximalaya
        "demo" -> R.drawable.plugin_demo
        "local" -> R.drawable.plugin_local
        else -> null
    }
    when {
        res != null -> Image(
            painter = painterResource(res),
            contentDescription = source?.name ?: "插件图标",
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f))
        )
        source != null && source.iconUrl.isNotBlank() -> ArtworkImage(
            imageUrl = source.iconUrl,
            label = source.name,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f))
        )
        else -> Icon(
            Icons.Filled.Extension,
            contentDescription = "插件图标",
            tint = Cyan,
            modifier = Modifier.size(size)
        )
    }
}

/** Compact name for the switcher button; the full name stays in the list. */
private fun sourceShortName(source: SourceInfo?): String {
    val name = source?.name?.takeIf { it.isNotBlank() } ?: source?.id ?: "无插件"
    if (name.contains("喜马拉雅")) return "喜马拉雅"
    return name.split("·").first().trim().ifBlank { name }
}

/** One rail row: icon and label side by side, label never wraps. */
@Composable
private fun RailItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    labelSize: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .clickable { onClick() }
            .background(
                if (selected) SurfaceHigh else Color.Transparent,
                RoundedCornerShape(width * 0.18f)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Cyan else TextMuted,
            modifier = Modifier.size(iconSize)
        )
        RailLabel(
            text = label,
            color = if (selected) Cyan else TextMuted,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = labelSize.sp,
                lineHeight = (labelSize * 1.35f).sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The left rail is now the driver's primary control surface:
 *
 * - top row: current plugin with a dropdown to switch plugins, search icon
 *   beside it;
 * - middle: categories declared by the active plugin's `navigation`, scrollable;
 * - bottom row: the small brand mark and the settings entry (which also hosts
 *   plugin management).
 *
 * Everything else is kept out of the rail so the right pane has maximum room
 * for content and the player bar.
 */
@Composable
private fun LeftRail(ui: MainViewModel.UiState, vm: MainViewModel, availableHeight: androidx.compose.ui.unit.Dp) {
    // Wider rail: plugin names and category labels stay on one line. On a car
    // panel the spare axis is horizontal, so this costs nothing vertically.
    val railWidth = maxOf(240.dp, minOf(availableHeight * 0.36f, 340.dp))
    val sourceIconSize = maxOf(40.dp, minOf(railWidth * 0.19f, 56.dp))
    val navIconSize = maxOf(30.dp, minOf(railWidth * 0.16f, 44.dp))
    // The first head-unit review called the rail labels too small to read at
    // arm's length. Derive from the rail width and clamp upward.
    val labelSize = maxOf(18f, minOf(railWidth.value * 0.105f, 28f))
    val sourceLabelSize = maxOf(20f, minOf(railWidth.value * 0.115f, 30f))
    val smallNavHeight = maxOf(72.dp, minOf(availableHeight * 0.10f, 104.dp))
    val sourceRowHeight = maxOf(84.dp, minOf(availableHeight * 0.12f, 118.dp))
    val activeSource = ui.sources.firstOrNull { it.id == ui.activeSourceId }
    var sourceMenuOpen by remember { mutableStateOf(false) }

    // The picker is an overlay sibling of the rail content, not a child:
    // opening it must never shift the search button, categories or brand row.
    Box(Modifier.width(railWidth).fillMaxHeight().background(Background)) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // The switcher is the only way to change source, so it reads as a
        // button: outlined card + explicit chevron, not an anonymous row.
        Row(
            Modifier.fillMaxWidth().height(sourceRowHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceHigh)
                .border(BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)), RoundedCornerShape(14.dp))
                .clickable { sourceMenuOpen = !sourceMenuOpen }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PluginIcon(activeSource, sourceIconSize)
            Spacer(Modifier.width(10.dp))
            RailLabel(
                text = sourceShortName(activeSource),
                color = Text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = sourceLabelSize.sp,
                    lineHeight = (sourceLabelSize * 1.25f).sp
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ArrowDropDown, contentDescription = "切换插件",
                tint = Cyan, modifier = Modifier.size(24.dp)
            )
        }
        // Local library is a source but has no plugin search endpoint.
        if (activeSource != null && activeSource.id != "local") {
            IconButton(
                onClick = { vm.navigate(Page.SEARCH) },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = "搜索",
                    tint = if (ui.page == Page.SEARCH) Cyan else TextMuted,
                    modifier = Modifier.size(navIconSize)
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // No hard-coded categories: the list exists only when the active
            // plugin declares navigation entries.
            activeSource?.navigation.orEmpty().forEachIndexed { index, item ->
                RailItemRow(
                    icon = navIcon(item, index),
                    label = item.label,
                    selected = ui.page == Page.HOME && item.key == ui.activeNav?.key,
                    height = smallNavHeight,
                    width = railWidth - 16.dp,
                    iconSize = navIconSize,
                    labelSize = labelSize
                ) { vm.selectNav(item) }
            }
            // Fixed 收藏 category for every source. Favourites are host-local
            // and keyed by sourceId, so each plugin (and the local library)
            // sees only the rows saved under it.
            if (activeSource != null) {
                RailItemRow(
                    icon = Icons.Filled.Favorite,
                    label = "收藏",
                    selected = ui.page == Page.HOME &&
                        ui.activeNav?.builtin == MainViewModel.FAVORITES_NAV_ID,
                    height = smallNavHeight,
                    width = railWidth - 16.dp,
                    iconSize = navIconSize,
                    labelSize = labelSize
                ) { vm.selectFavoriteNav() }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(76.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VoiceprintMark(size = railWidth * 0.22f)
            RailLabel(
                text = "希音",
                color = Cyan,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = sourceLabelSize.sp,
                    lineHeight = (sourceLabelSize * 1.2f).sp
                ),
                modifier = Modifier.weight(1f)
            )
            RailItemRow(
                icon = Icons.Filled.Settings,
                label = "设置",
                selected = ui.page == Page.SETTINGS || ui.page == Page.PLUGINS ||
                    ui.page == Page.SUPPORT,
                height = 56.dp,
                width = railWidth * 0.42f,
                iconSize = navIconSize * 0.8f,
                labelSize = labelSize * 0.92f
            ) { vm.navigate(Page.SETTINGS) }
        }
    }
    // Floating picker. It is drawn over the rail at the exact position of the
    // selector; the rail itself is untouched, so no button moves on open.
    if (sourceMenuOpen) {
        Box(Modifier.matchParentSize()) {
            // Transparent scrim: tapping anywhere outside closes the list.
            Box(Modifier.matchParentSize().clickable { sourceMenuOpen = false })
            Column(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 10.dp + sourceRowHeight + 6.dp)
                    .width(railWidth - 16.dp)
                    .heightIn(max = availableHeight * 0.48f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceHigh)
                    .border(BorderStroke(1.dp, Cyan.copy(alpha = 0.55f)), RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ui.sources.forEach { src ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                            .clickable {
                                sourceMenuOpen = false
                                if (src.id != ui.activeSourceId) vm.selectSource(src.id)
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PluginIcon(src, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        RailLabel(
                            text = sourceShortName(src),
                            color = if (src.id == ui.activeSourceId) Cyan else Text,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = sourceLabelSize.sp),
                            modifier = Modifier.weight(1f)
                        )
                        if (src.id == ui.activeSourceId) {
                            Icon(Icons.Filled.Check, contentDescription = "当前插件", tint = Cyan,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * One distinct glyph per category. The first pass mapped almost everything to
 * MusicNote, which made the rail read as a single repeated item in the car.
 * Semantics come first (page / builtin / manifest icon hint); unknown entries
 * fall back to a stable palette indexed by their position so two neighbours
 * can never share an icon.
 */
private fun navIcon(
    item: NavItem,
    index: Int
): androidx.compose.ui.graphics.vector.ImageVector {
    val page = item.page.lowercase()
    val hint = item.icon.lowercase()
    val builtin = item.builtin.lowercase()
    return when {
        builtin == "home" || hint == "home" -> Icons.Filled.Home
        // page wins over the manifest's generic "radio" hint: the cnradio
        // regions page also declares icon=radio, but must not look identical
        // to 全部电台.
        page == "regions" || hint == "location" -> Icons.Filled.Public
        builtin == "stations" || hint == "radio" -> Icons.Filled.GraphicEq
        builtin == "liked" || builtin == "favorites" -> Icons.Filled.Favorite
        builtin == "savedalbums" || hint == "album" -> Icons.Filled.Album
        builtin == "shows" || hint == "podcast" -> Icons.Filled.Podcasts
        page == "categories" || hint == "category" -> Icons.Filled.Category
        page == "replay" || hint == "replay" -> Icons.Filled.Replay
        page == "rank" || hint == "chart" -> Icons.Filled.Leaderboard
        page == "discover" -> Icons.Filled.Explore
        page == "subscriptions" -> Icons.Filled.Subscriptions
        page == "purchases" -> Icons.Filled.ShoppingBag
        page == "recent" || hint == "calendar" -> Icons.Filled.History
        page == "local-recent" || hint == "playlist" -> Icons.AutoMirrored.Filled.PlaylistPlay
        hint == "star" -> Icons.Filled.Star
        hint == "event" -> Icons.Filled.Event
        hint == "note" -> Icons.Filled.MusicNote
        else -> listOf(
            Icons.Filled.LibraryMusic,
            Icons.Filled.Audiotrack,
            Icons.Filled.Radio,
            Icons.AutoMirrored.Filled.QueueMusic,
            Icons.Filled.Headphones
        )[index % 5]
    }
}

@Composable
private fun ContentArea(ui: MainViewModel.UiState, vm: MainViewModel, modifier: Modifier) {
    val activeNavTitle = ui.activeNav?.label?.takeIf { it.isNotBlank() }
    val sourceTitle = ui.sources.firstOrNull { it.id == ui.activeSourceId }?.name ?: "首页"
    // The 收藏 category is a host-owned nav entry. Show the independent
    // 全部播放 button only there, never on a plugin's own browse page.
    val onFavoritesPage = ui.activeNav?.builtin == MainViewModel.FAVORITES_NAV_ID
    val favoritePlayableCount = if (onFavoritesPage) {
        ui.favorites.count {
            it.sourceId == ui.activeSourceId &&
                it.kind != com.andy8401.mygo.car.data.BrowseItem.Kind.LINK.name
        }
    } else 0
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        when (ui.page) {
            Page.HOME -> BrowseContent(title = activeNavTitle ?: sourceTitle,
                browse = ui.browse, vm = vm, favorites = ui.favorites,
                sourceId = ui.activeSourceId,
                playAllCount = favoritePlayableCount.takeIf { onFavoritesPage },
                playAllRunning = ui.favoritePlayAllRunning,
                onPlayAll = { vm.playFavoriteAll() })
            Page.BROWSE -> BrowseContent(title = "浏览", browse = ui.browse, vm = vm,
                favorites = ui.favorites, sourceId = ui.activeSourceId,
                showBack = true, onBack = { vm.navigateBack() })
            Page.SEARCH -> SearchContent(ui.query, ui.search, vm, ui.favorites, ui.activeSourceId)
            Page.LIBRARY -> BrowseContent(title = "本地曲库", browse = ui.browse, vm = vm, local = true,
                favorites = ui.favorites, sourceId = ui.activeSourceId)
            Page.PLUGINS -> PluginManagerContent(ui, vm, Modifier)
            Page.SETTINGS -> SettingsContent(ui, vm)
            Page.SUPPORT -> SupportContent(vm)
        }
    }
}

/** Host-local favourite helpers, keyed by the source that owns the row. */
private fun isFavorite(favorites: List<FavoriteItem>, sourceId: String, kind: String, id: String): Boolean =
    favorites.any { it.key == "$sourceId|$kind|$id" }

/**
 * Radio folder tiles (region:/cat: LINKs) must not be saved as a LINK row —
 * their channels are what the driver wants. The heart state comes from the
 * folder tag on those channel rows, not from a row for the folder itself.
 */
private fun isChannelFolder(item: com.andy8401.mygo.car.data.BrowseItem): Boolean =
    item.kind == com.andy8401.mygo.car.data.BrowseItem.Kind.LINK &&
        (item.id.startsWith("region:") || item.id.startsWith("cat:"))

private fun isFolderFavorite(favorites: List<FavoriteItem>, sourceId: String, folder: String): Boolean =
    favorites.any { it.sourceId == sourceId && it.folder == folder }

@Composable
private fun BrowseContent(
    title: String,
    browse: Browse?,
    vm: MainViewModel,
    local: Boolean = false,
    favorites: List<FavoriteItem> = emptyList(),
    sourceId: String = "",
    /** Non-null only on the 收藏 page: renders the independent 全部播放 button. */
    playAllCount: Int? = null,
    playAllRunning: Boolean = false,
    onPlayAll: () -> Unit = {},
    /** Detail/folder pages get a back arrow on the left and a BACK handler. */
    showBack: Boolean = false,
    onBack: () -> Unit = {}
) {
    // Hardware BACK and the on-screen arrow share one action: pop one detail
    // level. Only detail pages opt in, so category homes keep their rail-only nav.
    if (showBack) androidx.activity.compose.BackHandler { onBack() }
    // A detail page brings its own title ("二战全史…"); only the fixed pages
    // (首页 / 浏览 / 本地曲库) fall back to the label the caller passed in.
    val heading = browse?.title?.takeIf { it.isNotBlank() } ?: title
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Cyan,
                    modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            // The redesign keeps page headings quiet; a 30sp headline plus subtitle
            // used to leave the first row of cards no room for its caption.
            Text(heading, style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp), color = Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (browse != null && browse.subtitle.isNotBlank()) {
                Text(browse.subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                    color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (playAllCount != null) {
            val enabled = playAllCount > 0 && !playAllRunning
            val label = if (playAllRunning) "展开中…" else "全部播放 ($playAllCount)"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(if (enabled) Cyan else SurfaceHigh, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .then(if (enabled) Modifier.clickable { onPlayAll() } else Modifier)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "全部播放",
                    tint = if (enabled) Background else TextMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (enabled) Background else TextMuted,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp))
            }
        }
    }
    if (browse == null) {
        Text("加载中…", color = TextMuted, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        browse.sections.forEach { section ->
            // A detail page repeats its own name as the first section title;
            // printing it twice wastes a whole row on a short car screen.
            if (section.title.isNotBlank() && section.title != heading) {
                item {
                    SectionHeader(
                        section.title,
                        "播放全部".takeIf { section.items.any { it.kind == BrowseItem.Kind.TRACK } }
                    ) {
                        vm.playAll(section.items.mapNotNull { it.track })
                    }
                }
            }
            // Links without a page carry prose (a show's 简介), not a destination.
            val notes = section.items.filter { it.kind == BrowseItem.Kind.LINK && it.id.isBlank() }
            if (notes.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        notes.forEach {
                            Text(it.title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                                color = TextMuted, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            val tiles = section.items.filterNot { it.kind == BrowseItem.Kind.LINK && it.id.isBlank() }
            val cards = tiles.filterNot { it.kind == BrowseItem.Kind.TRACK }
            if (cards.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        // Five across, not the mock-up's four: the head-unit
                        // window is only 411dp tall, so four wider cards push
                        // their captions under the player bar.
                        cards.chunked(5).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { item ->
                                    val fav = item.toFavorite(sourceId)
                                    val folderCard = isChannelFolder(item)
                                    LibraryTile(
                                        item = item,
                                        onClick = { vm.playItem(item) },
                                        favorite = if (folderCard) isFolderFavorite(favorites, sourceId, item.id)
                                                   else isFavorite(favorites, sourceId, item.kind.name, item.id),
                                        onFavorite = if (item.id.isNotBlank()) {
                                            { if (folderCard) vm.toggleFavoriteFolder(item) else vm.toggleFavorite(fav) }
                                        } else null,
                                        // Desktop-style ▶: expand this album /
                                        // show into the queue. LINK tiles are
                                        // page jumps, so they keep no play badge.
                                        onPlay = if (item.id.isNotBlank() && item.kind != BrowseItem.Kind.LINK) {
                                            { vm.playBrowseItemAll(item) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val rows = tiles.filter { it.kind == BrowseItem.Kind.TRACK }
            if (rows.isNotEmpty()) {
                items(rows) { item ->
                    item.track?.let { track ->
                        val fav = item.toFavorite(sourceId)
                        TrackRow(
                            track = track,
                            playing = false,
                            onClick = { vm.playTrack(track, rows.mapNotNull { x -> x.track }) },
                            favorite = isFavorite(favorites, sourceId, item.kind.name, item.id),
                            onFavorite = if (item.id.isNotBlank()) {
                                { vm.toggleFavorite(fav) }
                            } else null
                        )
                    }
                }
            }
            // A paged section (a show's episodes) advertises "show:<id>:<next>"
            // in morePage; that goes back through the detail router, not browse.
            if (section.morePage.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().height(TouchTarget.dp), contentAlignment = Alignment.Center) {
                        Text("加载更多", color = Cyan, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                            modifier = Modifier.fillMaxWidth().height(TouchTarget.dp)
                                .clickable { vm.openBrowsePage(section.morePage) }
                                .padding(top = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        if (browse.sections.isEmpty()) {
            item { Text("这个页面没有内容", color = TextMuted, fontSize = 20.sp,
                modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    result: com.andy8401.mygo.car.data.SearchResult,
    vm: MainViewModel,
    favorites: List<FavoriteItem> = emptyList(),
    sourceId: String = ""
) {
    // Hardware BACK must leave a search page: the rail no longer has a
    // dedicated 搜索 button once the page is open.
    androidx.activity.compose.BackHandler { vm.navigate(Page.HOME) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.navigate(Page.HOME) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Cyan,
                    modifier = Modifier.size(28.dp))
            }
            Text("搜索", style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp), color = Text)
        }
        OutlinedTextField(
            value = query, onValueChange = { vm.search(it) },
            placeholder = { Text("歌曲、歌手、专辑", color = TextMuted, fontSize = 20.sp) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = Text),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            // Albums and shows come first: they used to be parsed away, and
            // putting them under 30 song rows would hide them just as well.
            val tiles = result.albums + result.shows + result.playlists + result.artists
            if (tiles.isNotEmpty()) {
                item { SectionHeader("专辑与节目") }
                item {
                    Row(Modifier.fillMaxWidth().height(TileHeight).horizontalScroll(rememberScrollState())) {
                        tiles.forEach { tile ->
                            val fav = tile.toFavorite(sourceId)
                            TileItem(
                                item = tile,
                                onClick = { vm.playItem(tile) },
                                favorite = isFavorite(favorites, sourceId, tile.kind.name, tile.id),
                                onFavorite = if (tile.id.isNotBlank()) {
                                    { vm.toggleFavorite(fav) }
                                } else null,
                                // Same ▶ as browse cards: play the whole
                                // album/show instead of just opening it.
                                onPlay = if (tile.id.isNotBlank() && tile.kind != BrowseItem.Kind.LINK) {
                                    { vm.playBrowseItemAll(tile) }
                                } else null
                            )
                        }
                    }
                }
            }
            if (result.tracks.isNotEmpty()) {
                item { SectionHeader("单曲与节目") }
            }
            items(result.tracks) { t ->
                TrackRow(
                    track = t,
                    playing = false,
                    onClick = { vm.playTrack(t, result.tracks) },
                    favorite = isFavorite(favorites, sourceId, "TRACK", t.id),
                    onFavorite = if (t.id.isNotBlank()) {
                        { vm.toggleFavorite(t.toFavorite(sourceId)) }
                    } else null
                )
            }
            if (result.tracks.isEmpty() && tiles.isEmpty() && query.isNotBlank()) {
                item {
                    Text(
                        "没有找到「$query」。换个关键词，或换一个音源试试。",
                        color = TextMuted, fontSize = 20.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(ui: MainViewModel.UiState, vm: MainViewModel) {
    Text("设置", style = MaterialTheme.typography.headlineLarge, color = Text)
    Column(
        Modifier.fillMaxSize().padding(top = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingRow("音质", ui.settings.quality) { q -> vm.setQuality(q) }
        SwitchRow("显示歌词", ui.settings.lyrics) { on -> vm.setLyricsEnabled(on) }
        Text(
            "插件管理 ›",
            color = Cyan, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            modifier = Modifier.clickable { vm.navigate(Page.PLUGINS) }.padding(vertical = 4.dp)
        )
        Text("安装、配置、更新与卸载插件；也支持 mygocar://install?repo= 链接。",
            color = TextMuted, fontSize = 18.sp)

        Text(
            if (ui.appUpdateChecking) "检查更新中…" else "检查更新 ›",
            color = if (ui.appUpdateChecking) TextMuted else Cyan,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            modifier = Modifier.clickable(enabled = !ui.appUpdateChecking && !ui.appUpdateDownloading) {
                vm.checkAppUpdate()
            }.padding(vertical = 4.dp)
        )
        Text("当前版本 ${BuildVersion.Name} · 点击检查车机 App 新版本",
            color = TextMuted, fontSize = 18.sp)

        // Voluntary support. No payment SDK, no order state and no feature
        // gate: the page only shows the maintainer's own collection codes.
        Text(
            "请我喝杯咖啡 ›",
            color = Cyan, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            modifier = Modifier.clickable { vm.navigate(Page.SUPPORT) }.padding(vertical = 4.dp)
        )
        Text("如果希音让路途更动听，欢迎请我喝杯咖啡。", color = TextMuted, fontSize = 18.sp)

        // AGPL-3.0 requires the fork to identify itself and say where the source
        // lives. Keep this section honest: it names the upstream project, this
        // branch, and the two different version numbers.
        Text("关于", style = MaterialTheme.typography.titleLarge, color = Text,
            modifier = Modifier.padding(top = 8.dp))
        Text("希音 ${BuildVersion.Name} · Android 车机版", color = Text, fontSize = 20.sp)
        Text("AGPL-3.0 衍生作品 · 上游 ${BuildVersion.UpstreamRepo}", color = TextMuted, fontSize = 18.sp)
        Text("本分支 ${BuildVersion.ForkRepo} · 完整源码随分发提供（NOTICE.md / LICENSE）",
            color = TextMuted, fontSize = 18.sp)
        Text("宿主能力版本 ${ui.version}：插件按 minAppVersion 校验，与 App 版本无关",
            color = TextMuted, fontSize = 18.sp)
    }
}

/**
 * Voluntary support page.
 *
 * This is deliberately not an in-app purchase: no payment SDK, no order
 * state, and no unlocked feature. The maintainer's collection QR codes are
 * bundled as images; the user scans them with a phone. Switching the tab
 * only changes which local image is shown.
 */
@Composable
private fun SupportContent(vm: MainViewModel) {
    androidx.activity.compose.BackHandler { vm.navigate(Page.SETTINGS) }
    var method by remember { mutableStateOf("wechat") }
    val qrPainter = painterResource(
        if (method == "wechat") R.drawable.donate_wechat else R.drawable.donate_alipay
    )
    val qrLabel = if (method == "wechat") "微信支付收款码" else "支付宝收款码"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.navigate(Page.SETTINGS) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    tint = Cyan, modifier = Modifier.size(28.dp))
            }
            Text("支持作者", style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp),
                color = Text)
        }
        Text(
            "如果希音让路途更动听，欢迎请我喝杯咖啡。",
            color = Text, fontSize = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        // Safety reminder is deliberately high-contrast and larger than the
        // surrounding notes: scanning a payment code needs a parked vehicle.
        // It sits above the QR so the vehicle's bottom player bar cannot hide it.
        Text(
            "请在驻车时操作",
            color = Cyan,
            fontSize = 24.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaymentTab("微信支付", method == "wechat") { method = "wechat" }
            PaymentTab("支付宝", method == "alipay") { method = "alipay" }
        }
        Box(
            Modifier.size(220.dp).clip(RoundedCornerShape(16.dp))
                .background(Color.White).padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = qrPainter,
                contentDescription = qrLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text("请用手机扫码 · 自愿支持 · 不影响任何功能", color = TextMuted, fontSize = 16.sp)
    }
}

/** Confirmation before downloading a sideloaded APK over HTTPS. */
@Composable
private fun AppUpdateDialog(info: MainViewModel.AppUpdateInfo, vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissAppUpdate() },
        containerColor = Surface,
        titleContentColor = Text,
        textContentColor = Text,
        title = {
            Text("发现新版本 ${info.version}", color = Text, fontSize = 26.sp)
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("当前版本 ${BuildVersion.Name}", color = TextMuted, fontSize = 18.sp)
                Text(info.notes.ifBlank { "暂无更新说明" }, color = Text, fontSize = 18.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.downloadAndInstallAppUpdate() }) {
                Text("下载并安装", color = Cyan, fontSize = 20.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissAppUpdate() }) {
                Text("稍后", color = TextMuted, fontSize = 20.sp)
            }
        }
    )
}

@Composable
private fun PaymentTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(150.dp).height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cyan else SurfaceHigh)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Background else Text, fontSize = 22.sp)
    }
}

/** UI labels for the persisted quality codes; the Go facade still sees the codes. */
private fun qualityLabel(code: String): String = when (code) {
    "low" -> "标准"
    "high" -> "高品质"
    "lossless" -> "无损"
    else -> code
}

@Composable
private fun SettingRow(label: String, value: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            color = Text, modifier = Modifier.weight(1f))
        listOf("low", "high", "lossless").forEach { q ->
            Text(qualityLabel(q), color = if (q == value) Cyan else TextMuted, fontSize = 20.sp,
                modifier = Modifier.clickable { onPick(q) }.padding(12.dp))
        }
    }
}

/** A labelled on/off row; the switch is right-aligned like SettingRow's choices. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            color = Text, modifier = Modifier.weight(1f))
        Text(if (checked) "开启" else "关闭", color = TextMuted,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
            modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Now-playing mini bar.
 *
 * Sized from the available content height (124–280dp) with generous floors
 * for cover, type and touch targets. The driver photo still showed a thin,
 * scattered strip, so the whole group is now centred instead of stretched:
 * cover + centred track text + five large controls sit together in the middle.
 */
@Composable
private fun PlayerBar(
    player: PlayerState,
    lyricsEnabled: Boolean,
    onOpen: () -> Unit,
    onOpenQueue: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    vm: MainViewModel,
    availableHeight: androidx.compose.ui.unit.Dp
) {
    // The first head-unit photo still showed a thin strip with tiny glyphs.
    // Raise every floor and derive sizes from a taller bar, but keep the
    // group centered: on a 32:10 panel a stretched row scatters the controls
    // too far apart for the driver.
    val barHeight = maxOf(124.dp, minOf(availableHeight * 0.24f, 280.dp))
    val coverSize = maxOf(90, minOf((barHeight.value * 0.68f).toInt(), 180))
    val sideIcon = maxOf(44.dp, minOf(barHeight * 0.36f, 80.dp))
    val playIcon = maxOf(56.dp, minOf(barHeight * 0.44f, 96.dp))
    val buttonSize = sideIcon + 12.dp
    val titleSize = maxOf(22f, minOf(barHeight.value * 0.17f, 34f))
    val subSize = maxOf(17f, minOf(barHeight.value * 0.13f, 25f))
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(barHeight).background(Surface)
            .clickable { onOpen() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            CoverImage(player.currentTrack?.name ?: "·", coverSize,
                imageUrl = player.currentTrack?.imageUrl ?: "")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(min = 220.dp, max = 380.dp)
            ) {
                Text(player.currentTrack?.name ?: "未播放", color = Text,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.currentTrack?.artistText ?: "选择一首歌开始", color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = subSize.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Host-local favourite of whatever is playing (song or station),
            // scoped to the owning source. No plugin API involved.
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(buttonSize)) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) Cyan else TextMuted,
                    modifier = Modifier.size(sideIcon)
                )
            }
            // The queue is one tap away from the bar: a head unit should not need
            // the full player for "what is coming next".
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放清单", tint = TextMuted,
                    modifier = Modifier.size(sideIcon))
            }
            IconButton(onClick = { vm.prev() }, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.Filled.SkipPrevious, "上一首", tint = Cyan,
                    modifier = Modifier.size(sideIcon))
            }
            IconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(buttonSize)) {
                Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (player.playing) "暂停" else "播放", tint = Cyan,
                    modifier = Modifier.size(playIcon))
            }
            IconButton(onClick = { vm.next() }, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.Filled.SkipNext, "下一首", tint = Cyan,
                    modifier = Modifier.size(sideIcon))
            }
        }
    }
}

@Composable
private fun ToastBubble(message: String, onDone: () -> Unit) {
    LaunchedEffectToast(message, onDone)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(message, color = Background, modifier = Modifier
            .padding(bottom = 100.dp)
            .background(Cyan, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun LaunchedEffectToast(message: String, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        kotlinx.coroutines.delay(2500)
        onDone()
    }
}
