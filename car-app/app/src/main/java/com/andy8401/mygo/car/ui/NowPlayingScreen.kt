package com.andy8401.mygo.car.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andy8401.mygo.car.data.LyricsText
import com.andy8401.mygo.car.data.PlayerState
import com.andy8401.mygo.car.data.Track
import com.andy8401.mygo.car.player.PlaybackHolder
import com.andy8401.mygo.car.ui.CarColors.Background
import com.andy8401.mygo.car.ui.CarColors.Espresso
import com.andy8401.mygo.car.ui.CarColors.Gold
import com.andy8401.mygo.car.ui.CarColors.Ink
import com.andy8401.mygo.car.ui.CarColors.Paper
import com.andy8401.mygo.car.ui.CarColors.SurfaceHigh
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted
import kotlinx.coroutines.delay

/**
 * MUSE player, laid out for a landscape head unit:
 *
 *  - 播放队列: large cover | track info, gold progress and transport | queue
 *  - 歌词:     small cover + lyrics on an espresso wash, minimal transport
 *
 * Both panes need the same two things the Go core deliberately does not own on
 * Android — the playback position (Media3 has it) and the lyrics (the plugin
 * has them) — so they are read here instead of being pushed through the bridge
 * twenty times a second.
 */
@Composable
fun NowPlayingSheet(ui: MainViewModel.UiState, vm: MainViewModel) {
    BackHandler { vm.closePlayer() }
    val player = ui.player
    val position = rememberPlaybackPosition(player.currentTrack?.id ?: "")
    val name = player.currentTrack?.name ?: "未播放"

    Box(Modifier.fillMaxSize().background(Background)) {
        when (ui.playerPane) {
            MainViewModel.PlayerPane.LYRICS -> LyricsStage(ui, vm, player, position, name)
            MainViewModel.PlayerPane.QUEUE -> PlayerStage(ui, vm, player, position, name)
        }
    }
}

/** Polls Media3 for the playback position. ExoPlayer is owned by the main
 *  thread, and a LaunchedEffect body already runs on the Compose UI dispatcher,
 *  so this must not be moved to Dispatchers.IO. */
@Composable
private fun rememberPlaybackPosition(trackId: String): Long {
    var position by remember(trackId) { mutableStateOf(0L) }
    LaunchedEffect(trackId) {
        while (true) {
            position = PlaybackHolder.current()?.currentPosition ?: 0L
            delay(250)
        }
    }
    return position
}

@Composable
private fun PaneSwitch(ui: MainViewModel.UiState, vm: MainViewModel) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            // Same voiceprint mark as the launcher and left rail, tinted to the
            // player's gold accent instead of the old MUSE wordmark.
            VoiceprintMark(size = 36.dp, color = Gold)
            Spacer(Modifier.width(24.dp))
            if (ui.settings.lyrics) {
                PaneTab("歌词", ui.playerPane == MainViewModel.PlayerPane.LYRICS) {
                    vm.setPlayerPane(MainViewModel.PlayerPane.LYRICS)
                }
                Spacer(Modifier.width(8.dp))
            }
            PaneTab("播放清单 ${ui.player.length}", ui.playerPane == MainViewModel.PlayerPane.QUEUE) {
                vm.setPlayerPane(MainViewModel.PlayerPane.QUEUE)
            }
        }
        // Down arrow is the only close affordance; the old 专注 entry and the
        // whole driving-focus mode were removed from the player header.
        // It is anchored to the horizontal centre of the whole player header
        // rather than being pushed to the far right by a weight spacer.
        Box(
            Modifier.align(Alignment.Center).size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { vm.closePlayer() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "收起播放界面",
                    tint = Gold,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun PaneTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Gold else TextMuted,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        modifier = Modifier
            .background(if (selected) SurfaceHigh else Background, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ------------------------------------------------------------- player stage

@Composable
private fun PlayerStage(
    ui: MainViewModel.UiState, vm: MainViewModel, player: PlayerState,
    position: Long, name: String
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 32.dp, vertical = 12.dp)
    ) {
        PaneSwitch(ui, vm)
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork gets the whole left third. The cover is the visual anchor
            // of the redesign; a small thumbnail would lose the whole effect.
            Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                CoverImageFill(name, Modifier.fillMaxWidth(0.92f).aspectRatio(1f),
                    imageUrl = player.currentTrack?.imageUrl ?: "")
            }
            Spacer(Modifier.width(32.dp))

            Column(
                Modifier.weight(1.25f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(name, style = MaterialTheme.typography.headlineLarge, color = Paper,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.currentTrack?.artistText ?: "选择一首歌开始",
                    style = MaterialTheme.typography.bodyLarge, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("《${player.currentTrack?.album?.name ?: "播放中"}》· 24bit/96kHz 无损",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))

                Progress(player, position) { vm.seekTo(it) }
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayModeIcon(
                        Icons.Filled.Shuffle,
                        if (ui.player.shuffle) "关闭随机播放" else "开启随机播放",
                        ui.player.shuffle
                    ) { vm.toggleShuffle() }
                    Spacer(Modifier.width(16.dp))
                    TransportIcon(Icons.Filled.SkipPrevious, "上一首", 38.dp) { vm.prev() }
                    Spacer(Modifier.width(28.dp))
                    PlayButton(player.playing, 76.dp) { vm.togglePlay() }
                    Spacer(Modifier.width(28.dp))
                    TransportIcon(Icons.Filled.SkipNext, "下一首", 38.dp) { vm.next() }
                    Spacer(Modifier.width(16.dp))
                    PlayModeIcon(
                        if (ui.player.repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        when (ui.player.repeat) {
                            "one" -> "单曲循环，点击改为不循环"
                            "all" -> "清单循环，点击改为单曲循环"
                            else -> "不循环，点击改为清单循环"
                        },
                        ui.player.repeat != "off"
                    ) { vm.cycleRepeat() }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityChip("无损 24/96")
                    QualityChip("空间音频")
                    QualityChip("输出 · 车载扬声器")
                }
            }
            Spacer(Modifier.width(28.dp))

            // Queue panel is its own raised surface, as in the mock-up.
            Column(
                Modifier.width(300.dp).fillMaxHeight()
                    .background(SurfaceHigh, RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("播放队列", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Spacer(Modifier.weight(1f))
                    Text("${player.length} 首", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                Spacer(Modifier.height(8.dp))
                QueuePane(player) { vm.playAt(it) }
            }
        }
    }
}

/** Mode buttons need a car-sized hit target even though the glyph is small. */
@Composable
private fun PlayModeIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = if (active) Gold else TextMuted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QualityChip(label: String) {
    Text(label, color = Gold, style = MaterialTheme.typography.bodySmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(Background, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun PlayButton(playing: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Paper, RoundedCornerShape(size / 2))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "暂停" else "播放",
            tint = Ink, modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    size: androidx.compose.ui.unit.Dp, onClick: () -> Unit
) {
    Box(Modifier.size(size).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Paper, modifier = Modifier.size(size))
    }
}

@Composable
private fun Progress(player: PlayerState, positionMs: Long, onSeek: (Long) -> Unit) {
    val duration = player.currentTrack?.durationMs ?: 0
    if (duration <= 0) {
        Text("--:--", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    var drag by remember(player.currentTrack?.id) { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(fmtTime((drag?.let { it * duration } ?: positionMs.toFloat()).toLong()),
            color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = drag ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = SurfaceHigh
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Text(fmtTime(duration.toLong()), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ lyrics

@Composable
private fun LyricsStage(
    ui: MainViewModel.UiState, vm: MainViewModel, player: PlayerState,
    position: Long, name: String
) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Espresso, Ink)))
            .statusBarsPadding().padding(horizontal = 32.dp, vertical = 12.dp)
    ) {
        PaneSwitch(ui, vm)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(180.dp)) {
                CoverImageFill(name, Modifier.fillMaxWidth().aspectRatio(1f),
                    imageUrl = player.currentTrack?.imageUrl ?: "")
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.titleLarge, color = Paper,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.currentTrack?.artistText ?: "",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LyricsPane(ui, position) { vm.seekTo(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Progress(player, position) { vm.seekTo(it) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            TransportIcon(Icons.Filled.SkipPrevious, "上一首", 40.dp) { vm.prev() }
            Spacer(Modifier.width(36.dp))
            PlayButton(player.playing, 72.dp) { vm.togglePlay() }
            Spacer(Modifier.width(36.dp))
            TransportIcon(Icons.Filled.SkipNext, "下一首", 40.dp) { vm.next() }
        }
    }
}

@Composable
private fun LyricsPane(ui: MainViewModel.UiState, positionMs: Long, onSeek: (Long) -> Unit) {
    val lyrics = ui.lyrics
    when {
        lyrics == null && ui.lyricsLoading -> Empty("歌词加载中…")
        lyrics == null -> Empty("播放一首歌后显示歌词")
        lyrics.lines.isEmpty() -> Empty(lyrics.reason.ifBlank { "这首歌没有歌词" })
        else -> LyricList(lyrics, positionMs, onSeek)
    }
}

@Composable
private fun LyricList(lyrics: LyricsText, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    // indexOfLast returns -1 before the first line starts; the first line is
    // still the one to highlight then.
    val active = remember(lyrics.lines, positionMs) {
        lyrics.lines.indexOfLast { it.start <= positionMs }.coerceAtLeast(0)
    }
    LaunchedEffect(active, lyrics) {
        // Keep the sung line near the middle instead of glued to the top.
        listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == active
            Column(
                Modifier.fillMaxWidth().clickable { onSeek(line.start) }
                    .padding(vertical = 8.dp, horizontal = 6.dp)
            ) {
                Text(
                    line.text,
                    style = if (current) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.titleMedium,
                    color = if (current) Paper else TextMuted
                )
                if (line.translation.isNotBlank()) {
                    Text(
                        line.translation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current) Gold else TextMuted.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextMuted, style = MaterialTheme.typography.titleMedium)
    }
}

// ------------------------------------------------------------------- queue

@Composable
private fun QueuePane(player: PlayerState, onPlayAt: (Int) -> Unit) {
    if (player.queue.isEmpty()) {
        Empty("播放清单是空的")
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(player.index) {
        if (player.index in player.queue.indices) listState.animateScrollToItem(player.index)
    }
    // A live station's rows are a rolling window, so tapping one cannot mean
    // "play that"; rows stay visible but inert.
    val jumpable = player.stationId.isBlank()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(player.queue) { i, track ->
            QueueRow(track, i, i == player.index, jumpable) { onPlayAt(i) }
        }
        if (!jumpable) {
            item {
                Text("正在直播：清单由电台推送，只能逐条往下播放",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track, index: Int, current: Boolean, jumpable: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (jumpable) Modifier.clickable { onClick() } else Modifier)
            .background(if (current) Espresso else Background, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(Modifier.width(32.dp)) {
            Text(if (current) "♪" else "${index + 1}",
                color = if (current) Gold else TextMuted,
                style = MaterialTheme.typography.bodyLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(track.name, color = if (current) Gold else Text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (track.artistText.isNotBlank()) {
                Text(track.artistText, color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(fmtTime(track.durationMs), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** m:ss, or --:-- for a live stream / unknown length. */
private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
