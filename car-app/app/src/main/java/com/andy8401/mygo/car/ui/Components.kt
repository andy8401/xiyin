package com.andy8401.mygo.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andy8401.mygo.car.data.BrowseItem
import com.andy8401.mygo.car.data.Track
import com.andy8401.mygo.car.ui.CarColors.Cyan
import com.andy8401.mygo.car.ui.CarColors.SurfaceHigh
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.sin

/**
 * MUSE cover art: a quiet two-tone gradient with one abstract motif. The
 * redesign uses artwork as the only place a gradient is allowed — chrome
 * stays flat ink/gold. Four motifs are enough to keep neighbouring covers
 * visually distinct without downloading anything or blocking on the network.
 */
private val CoverPalettes = listOf(
    Color(0xFFD98A55) to Color(0xFF6B3A66), // sunset / sound rings
    Color(0xFF4E737B) to Color(0xFF2B4952), // tide / waves
    Color(0xFFA46F6E) to Color(0xFF654450), // dusk / disc
    Color(0xFF6B5D8C) to Color(0xFF3D3454), // violet / ridge
    Color(0xFF61745C) to Color(0xFF31402F)  // moss / waveform
)

@Composable
fun MuseCover(label: String, modifier: Modifier = Modifier) {
    val seed = label.hashCode()
    val palette = CoverPalettes[abs(seed / 7) % CoverPalettes.size]
    val motif = abs(seed) % 4
    Box(
        modifier.background(
            Brush.linearGradient(listOf(palette.first, palette.second)),
            RoundedCornerShape(10.dp)
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = minOf(size.width, size.height)
            val hairline = Stroke(width = maxOf(1f, unit * 0.006f))
            when (motif) {
                // Sound rings: the identity system's core symbol, kept on the
                // default cover so the brand reads even with no artwork.
                0 -> {
                    for (i in 1..5) {
                        drawCircle(
                            color = Color(0x44F2F2F2), radius = unit * i / 11f,
                            center = Offset(cx, cy), style = hairline
                        )
                    }
                    drawCircle(
                        color = Color(0xFFD8A96B), radius = unit * 0.115f,
                        center = Offset(cx, cy)
                    )
                }
                1 -> {
                    for (row in 0..2) {
                        val path = Path()
                        val base = cy - unit * 0.16f + row * unit * 0.16f
                        path.moveTo(0f, base)
                        for (x in 0..size.width.toInt() step 8) {
                            path.lineTo(x.toFloat(), base + sin(x / unit * 6f + row) * unit * 0.055f)
                        }
                        drawPath(path, Color(0x66F2F2F2), style = hairline)
                    }
                }
                2 -> {
                    drawCircle(
                        color = Color(0x66F2F2F2),
                        radius = unit * 0.16f,
                        center = Offset(size.width * 0.66f, size.height * 0.28f)
                    )
                    val diagonal = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width, size.height * 0.42f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(diagonal, Color(0x26F2F2F2))
                }
                3 -> {
                    val ridge = Path().apply {
                        moveTo(0f, size.height * 0.78f)
                        lineTo(size.width * 0.24f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.68f)
                        lineTo(size.width * 0.68f, size.height * 0.36f)
                        lineTo(size.width, size.height * 0.74f)
                    }
                    drawPath(ridge, Color(0x77F2F2F2), style = hairline)
                    drawCircle(
                        color = Color(0x55F2F2F2), radius = unit * 0.09f,
                        center = Offset(size.width * 0.8f, size.height * 0.24f)
                    )
                }
            }
        }
    }
}

/**
 * Artwork that prefers the plugin's online cover image and falls back to the
 * locally drawn [MuseCover] when there is no URL or the fetch fails.
 *
 * Plugins (ximalaya-web, cnradio, ...) hand us a real album/track cover URL in
 * music.ImageURL; it is parsed all the way into Track/BrowseItem.imageUrl. The
 * redesign originally ignored it and drew a local gradient for every cover,
 * which is why no online artwork ever appeared. This is the single place that
 * decides online-first / local-fallback, so every cover site gets the behavior.
 */
@Composable
fun ArtworkImage(
    imageUrl: String,
    label: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (imageUrl.isBlank()) {
        MuseCover(label, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(imageUrl)
            .crossfade(true)
            .build(),
        contentDescription = label,
        contentScale = contentScale,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            else -> MuseCover(label, Modifier.fillMaxSize())
        }
    }
}

/** Small square cover used in rows and tiles. */
@Composable
fun CoverImage(
    label: String, size: Int, modifier: Modifier = Modifier, imageUrl: String = ""
) {
    ArtworkImage(imageUrl, label, modifier.size(size.dp))
}

/** Flexible square cover used by the player sheet. */
@Composable
fun CoverImageFill(label: String, modifier: Modifier = Modifier, imageUrl: String = "") {
    ArtworkImage(imageUrl, label, modifier)
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Text)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(action, color = Cyan, modifier = Modifier.clickable { onAction() }.padding(8.dp))
        }
    }
}

@Composable
fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).clickable { onClick() }.padding(horizontal = 4.dp)
    ) {
        CoverImage(track.name, 48, imageUrl = track.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyLarge,
                color = if (playing) Cyan else Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistText, style = MaterialTheme.typography.bodyMedium,
                color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Height of one cover tile, artwork included. Callers size the strip to this
 *  so a short toolbar cannot squash the tile into a sliver.
 *
 *  Sized for a 411dp-tall head-unit window: at 136dp the tile ate a third of
 *  the result area and pushed its own caption under the bottom bar. */
val TileHeight = 116.dp
const val TileCover = 88

@Composable
fun TileItem(item: BrowseItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(120.dp).height(TileHeight).clickable { onClick() }.padding(4.dp)
    ) {
        // size() is only a preference: a cramped parent shrinks the cover and
        // squashes the caption to a few pixels. Claim the height explicitly.
        CoverImage(item.title, TileCover, Modifier.height(TileCover.dp), imageUrl = item.imageUrl)
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) {
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Library card: the redesign uses a 4-across grid of landscape artwork with
 * the caption underneath. Unlike TileItem it lives in a non-scrolling Row and
 * shares the width, so it cannot be given a fixed dp size.
 */
@Composable
fun RowScope.LibraryTile(item: BrowseItem, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp).clickable { onClick() }
    ) {
        // 1.7 keeps the caption above the player bar on a 411dp-tall window;
        // the spec's taller artwork only fits when the bar is hidden.
        ArtworkImage(item.imageUrl, item.title, Modifier.fillMaxWidth().aspectRatio(1.9f))
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NavButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(56.dp)
            .height((TouchTarget + 4).dp) // compact rail: 60dp, still >= 56dp touch target
            .clickable { onClick() }
            .background(if (selected) SurfaceHigh else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Cyan else TextMuted,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        // MUSE's rail is icon-led; the tiny label keeps it glanceable on a
        // head unit (and reachable by the UI tests) without a text-heavy bar.
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (selected) Cyan else TextMuted, maxLines = 1)
    }
}
