package com.andy8401.mygo.car.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andy8401.mygo.car.BuildVersion
import com.andy8401.mygo.car.data.Browse
import com.andy8401.mygo.car.data.BrowseItem
import com.andy8401.mygo.car.data.PlayerState
import com.andy8401.mygo.car.data.SourceInfo
import com.andy8401.mygo.car.data.Track
import com.andy8401.mygo.car.ui.CarColors.Background
import com.andy8401.mygo.car.ui.CarColors.Cyan
import com.andy8401.mygo.car.ui.CarColors.Surface
import com.andy8401.mygo.car.ui.CarColors.SurfaceHigh
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted
import com.andy8401.mygo.car.ui.MainViewModel.Page

/**
 * Landscape car layout with a 16:9 default and a 32:9 maximized player.
 *
 * Every page renders inside the safe area inset by the system bars. By default
 * the app content is locked to 16:9 and centred, so on an ultra-wide head unit
 * (Lynk & Co 900, 32:10) it does NOT stretch or overflow the vehicle's bottom
 * bar. Only the playing page (HOME) may be maximized: tapping its button fills
 * the whole safe area, which on a 32:9 panel gives the immersive wide layout.
 * Leaving HOME restores the 16:9 default automatically (see ViewModel.navigate).
 */
@Composable
fun CarScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                    LeftRail(ui.page, vm, contentHeight)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            ContentArea(ui, vm, Modifier.weight(1f))
                            PlayerBar(
                                player = ui.player,
                                lyricsEnabled = ui.settings.lyrics,
                                onOpen = { vm.openPlayer() },
                                onOpenQueue = { vm.openPlayer(MainViewModel.PlayerPane.QUEUE) },
                                vm = vm
                            )
                        }
                    }
                }
                // 最大化切换只在播放界面（首页）出现。
                if (isPlayingPage) {
                    MaximizeToggle(
                        maximized = wide,
                        onToggle = { vm.toggleMaximized() },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
                if (ui.playerOpen) {
                    NowPlayingSheet(ui, vm)
                }
                // The plugin form and the uninstall prompt both outrank the player
                // sheet: they are short-lived and the user is mid-task. The sign-in
                // card sits on top of all of them.
                PluginSettingsSheet(ui, vm)
                UninstallDialog(ui, vm)
                SignInPromptSheet(ui, vm)
                if (ui.message.isNotBlank()) {
                    ToastBubble(ui.message, onDone = { vm.dismissMessage() })
                }
                // The driving view outranks everything, including sign-in.
                if (ui.focusMode) FocusModeScreen(ui, vm)
            }
        }
    }
}

/** 播放界面右上角的 16:9 ↔ 32:9 切换按钮。 */
@Composable
private fun MaximizeToggle(maximized: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val icon = if (maximized) Icons.Filled.ZoomOut else Icons.Filled.ZoomIn
    val label = if (maximized) "还原" else "最大化"
    Row(
        modifier
            .padding(top = 20.dp, end = 20.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (maximized) SurfaceHigh else Surface)
            .clickable { onToggle() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Cyan, modifier = Modifier.size(28.dp))
        Text(label, color = Cyan, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The left navigation rail, scaled to the usable panel height.
 *
 * The old fixed 64dp rail was sized for a short 411dp window and reads as a
 * hairline on a 900-class wide screen. Deriving it from maxHeight makes the
 * rail, its icons and its labels grow with the car panel while staying inside
 * the system-bar safe area that CarScreen already established.
 */
@Composable
private fun LeftRail(page: Page, vm: MainViewModel, availableHeight: androidx.compose.ui.unit.Dp) {
    // ~9% of the usable height, clamped so a phone stays compact and a 6K car
    // panel gets a comfortably tappable rail (96–180dp).
    val railWidth = maxOf(96.dp, minOf(availableHeight * 0.09f, 180.dp))
    val navHeight = maxOf(76.dp, minOf(availableHeight / 7f, 132.dp))
    val iconSize = maxOf(26.dp, minOf(railWidth * 0.34f, 52.dp))
    val labelSize = maxOf(12f, minOf(railWidth.value * 0.14f, 20f))
    Column(
        Modifier.width(railWidth).fillMaxHeight().background(Surface)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // MUSE wordmark reduced to its initial: the icon-led rail has no room
        // for a full name, and the brand is spelled out on the settings page.
        Text("M", style = MaterialTheme.typography.titleLarge.copy(fontSize = labelSize.sp * 1.6f), color = Cyan)
        NavButton(Icons.Filled.GraphicEq, "首页", page == Page.HOME, navHeight, railWidth, iconSize, labelSize) { vm.navigate(Page.HOME) }
        NavButton(Icons.Filled.MusicNote, "搜索", page == Page.SEARCH, navHeight, railWidth, iconSize, labelSize) { vm.navigate(Page.SEARCH) }
        NavButton(Icons.Filled.Radio, "本地", page == Page.LIBRARY, navHeight, railWidth, iconSize, labelSize) { vm.navigate(Page.LIBRARY) }
        NavButton(Icons.Filled.Mic, "插件", page == Page.PLUGINS, navHeight, railWidth, iconSize, labelSize) { vm.navigate(Page.PLUGINS) }
        NavButton(Icons.Filled.Tune, "设置", page == Page.SETTINGS, navHeight, railWidth, iconSize, labelSize) { vm.navigate(Page.SETTINGS) }
    }
}

/**
 * Source switcher as a horizontal chip strip.
 *
 * A vertical source list cannot live in the rail any more: five nav buttons
 * already fill a 411dp-tall car screen, which used to squeeze the source list
 * to zero height and make switching sources impossible. The head unit is wide,
 * so the spare axis is horizontal.
 */
@Composable
private fun SourceBar(sources: List<SourceInfo>, activeId: String, vm: MainViewModel) {
    if (sources.size <= 1) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sources.forEach { src ->
            val selected = src.id == activeId
            Text(
                src.name,
                color = if (selected) Background else TextMuted,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier
                    .background(if (selected) Cyan else SurfaceHigh, RoundedCornerShape(18.dp))
                    .clickable { vm.selectSource(src.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ContentArea(ui: MainViewModel.UiState, vm: MainViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        // Switching source only matters for content pages; the manager and the
        // app settings are global.
        if (ui.page == Page.HOME || ui.page == Page.BROWSE || ui.page == Page.SEARCH || ui.page == Page.LIBRARY) {
            SourceBar(ui.sources, ui.activeSourceId, vm)
        }
        when (ui.page) {
            Page.HOME -> BrowseContent(title = ui.sources.firstOrNull { it.id == ui.activeSourceId }?.name ?: "首页",
                browse = ui.browse, vm = vm)
            Page.BROWSE -> BrowseContent(title = "浏览", browse = ui.browse, vm = vm)
            Page.SEARCH -> SearchContent(ui.query, ui.search, vm)
            Page.LIBRARY -> BrowseContent(title = "本地曲库", browse = ui.browse, vm = vm, local = true)
            Page.PLUGINS -> PluginManagerContent(ui, vm, Modifier)
            Page.SETTINGS -> SettingsContent(ui, vm)
        }
    }
}

@Composable
private fun BrowseContent(title: String, browse: Browse?, vm: MainViewModel, local: Boolean = false) {
    // A detail page brings its own title ("二战全史…"); only the fixed pages
    // (首页 / 浏览 / 本地曲库) fall back to the label the caller passed in.
    val heading = browse?.title?.takeIf { it.isNotBlank() } ?: title
    // The redesign keeps page headings quiet; a 30sp headline plus subtitle
    // used to leave the first row of cards no room for its caption.
    Text(heading, style = MaterialTheme.typography.titleLarge, color = Text,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (browse != null && browse.subtitle.isNotBlank()) {
        Text(browse.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (browse == null) {
        Text("加载中…", color = TextMuted, modifier = Modifier.padding(top = 24.dp))
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
                            Text(it.title, style = MaterialTheme.typography.bodyMedium,
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
                                row.forEach { LibraryTile(it) { vm.playItem(it) } }
                            }
                        }
                    }
                }
            }
            val rows = tiles.filter { it.kind == BrowseItem.Kind.TRACK }
            if (rows.isNotEmpty()) {
                items(rows) { item ->
                    item.track?.let { TrackRow(it, false) { vm.playTrack(it, rows.mapNotNull { x -> x.track }) } }
                }
            }
            // A paged section (a show's episodes) advertises "show:<id>:<next>"
            // in morePage; that goes back through the detail router, not browse.
            if (section.morePage.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().height(TouchTarget.dp), contentAlignment = Alignment.Center) {
                        Text("加载更多", color = Cyan, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().height(TouchTarget.dp)
                                .clickable { vm.openBrowsePage(section.morePage) }
                                .padding(top = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        if (browse.sections.isEmpty()) {
            item { Text("这个页面没有内容", color = TextMuted, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun SearchContent(query: String, result: com.andy8401.mygo.car.data.SearchResult, vm: MainViewModel) {
    // No title row above the field: the rail already highlights 搜索, and a
    // 43dp headline on a 411dp-tall window left the result list a letterbox
    // that squashed every cover tile to a sliver.
    OutlinedTextField(
        value = query, onValueChange = { vm.search(it) },
        placeholder = { Text("歌曲、歌手、专辑", color = TextMuted) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        // Albums and shows come first: they used to be parsed away, and putting
        // them under 30 song rows would hide them just as well.
        val tiles = result.albums + result.shows + result.playlists + result.artists
        if (tiles.isNotEmpty()) {
            item { SectionHeader("专辑与节目") }
            item {
                Row(Modifier.fillMaxWidth().height(TileHeight).horizontalScroll(rememberScrollState())) {
                    tiles.forEach { TileItem(it) { vm.playItem(it) } }
                }
            }
        }
        if (result.tracks.isNotEmpty()) {
            item { SectionHeader("单曲与节目") }
        }
        items(result.tracks) { t -> TrackRow(t, false) { vm.playTrack(t, result.tracks) } }
        if (result.tracks.isEmpty() && tiles.isEmpty() && query.isNotBlank()) {
            item {
                Text(
                    "没有找到「$query」。换个关键词，或换一个音源试试。",
                    color = TextMuted,
                    modifier = Modifier.padding(top = 16.dp)
                )
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
        Text("插件安装、配置、更新与卸载请用左侧「插件」页；也支持 mygocar://install?repo= 链接。", color = TextMuted)

        // AGPL-3.0 requires the fork to identify itself and say where the source
        // lives. Keep this section honest: it names the upstream project, this
        // branch, and the two different version numbers.
        Text("关于", style = MaterialTheme.typography.titleLarge, color = Text,
            modifier = Modifier.padding(top = 8.dp))
        Text("希音 ${BuildVersion.Name} · Android 车机版", color = Text)
        Text("AGPL-3.0 衍生作品 · 上游 ${BuildVersion.UpstreamRepo}", color = TextMuted)
        Text("本分支 ${BuildVersion.ForkRepo} · 完整源码随分发提供（NOTICE.md / LICENSE）", color = TextMuted)
        Text("宿主能力版本 ${ui.version}：插件按 minAppVersion 校验，与 App 版本无关", color = TextMuted)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Text, modifier = Modifier.weight(1f))
        listOf("low", "high", "lossless").forEach { q ->
            Text(q, color = if (q == value) Cyan else TextMuted,
                modifier = Modifier.clickable { onPick(q) }.padding(12.dp))
        }
    }
}

/** A labelled on/off row; the switch is right-aligned like SettingRow's choices. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Text, modifier = Modifier.weight(1f))
        Text(if (checked) "开启" else "关闭", color = TextMuted,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PlayerBar(
    player: PlayerState,
    lyricsEnabled: Boolean,
    onOpen: () -> Unit,
    onOpenQueue: () -> Unit,
    vm: MainViewModel
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(88.dp).background(Surface)
            .clickable { onOpen() }.padding(horizontal = 16.dp)
    ) {
        CoverImage(player.currentTrack?.name ?: "·", 56,
            imageUrl = player.currentTrack?.imageUrl ?: "")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(player.currentTrack?.name ?: "未播放", color = Text,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(player.currentTrack?.artistText ?: "选择一首歌开始", color = TextMuted,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        // The queue is one tap away from the bar: a head unit should not need
        // the full player for "what is coming next".
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放清单", tint = TextMuted,
                modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { vm.prev() }) { Icon(Icons.Filled.SkipPrevious, "上一首", tint = Cyan, modifier = Modifier.size(32.dp)) }
        IconButton(onClick = { vm.togglePlay() }) {
            Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (player.playing) "暂停" else "播放", tint = Cyan, modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = { vm.next() }) { Icon(Icons.Filled.SkipNext, "下一首", tint = Cyan, modifier = Modifier.size(32.dp)) }
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
