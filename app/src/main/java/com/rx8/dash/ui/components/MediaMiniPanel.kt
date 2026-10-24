package com.rx8.dash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rx8.dash.media.MediaPanelController
import com.rx8.dash.ui.theme.LocalDashColors

/**
 * Media mini-panel. This is the only UI file that touches [MediaPanelController]; it relies on exactly:
 * `nowPlaying` (StateFlow of a nullable now-playing value with `title` and `artist`), `accessState` (StateFlow, shown via
 * toString), and non-suspend `play()`, `pause()`, `next()`, `previous()`.
 */
@Composable
fun MediaMiniPanel(controller: MediaPanelController, modifier: Modifier = Modifier) {
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val state by controller.accessState.collectAsStateWithLifecycle()
    val np = nowPlaying
    val title: String = np?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing"
    val artist: String = np?.artist?.toString() ?: ""
    MediaMiniPanelContent(
        title = title,
        subtitle = if (artist.isNotBlank()) artist else state.toString().replace('_', ' ').lowercase(),
        onPrevious = { controller.previous() },
        onPlay = { controller.play() },
        onPause = { controller.pause() },
        onNext = { controller.next() },
        modifier = modifier,
    )
}

@Composable
fun MediaMiniPanelContent(
    title: String,
    subtitle: String,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalDashColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MediaButton(Icons.Filled.SkipPrevious, "Previous", onPrevious)
        MediaButton(Icons.Filled.PlayArrow, "Play", onPlay)
        MediaButton(Icons.Filled.Pause, "Pause", onPause)
        MediaButton(Icons.Filled.SkipNext, "Next", onNext)
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalDashColors.current
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = label, tint = c.text, modifier = Modifier.size(32.dp))
    }
}
