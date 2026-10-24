package com.rx8.dash.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.ui.theme.LocalDashColors
import kotlinx.coroutines.delay

/**
 * Start-up intro: the rotor and the "MAZDA RX-8" wordmark fade in over a dark screen, hold briefly, then the whole
 * overlay fades out to reveal the dashboard underneath. Tap anywhere to skip. [onFinished] runs once at the end.
 */
@Composable
fun IntroSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    val reveal = remember { Animatable(0f) }   // logo and text fade-in
    val line = remember { Animatable(0f) }     // accent line growing under the wordmark
    val fade = remember { Animatable(1f) }     // whole overlay, 1 = opaque

    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = 900))
        delay(1000)
        fade.animateTo(0f, tween(durationMillis = 700))
        onFinished()
    }
    LaunchedEffect(Unit) {
        delay(300)
        line.animateTo(1f, tween(durationMillis = 1100, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(c.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = reveal.value
                val scale = 0.92f + 0.08f * reveal.value
                scaleX = scale
                scaleY = scale
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RotorEmblem(rpm = 2500.0, modifier = Modifier.size(150.dp))
            Spacer(Modifier.height(10.dp))
            // Wide-tracked capitals, in the manner of the Mazda wordmark. This is lettering, not the official logo.
            Text(
                text = "MAZDA",
                color = c.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 14.sp,
            )
            Text(
                text = "RX-8",
                color = c.accent,
                fontSize = 84.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width((260f * line.value).dp)
                    .height(3.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "RENESIS  13B-MSP",
                color = c.textDim,
                fontSize = 14.sp,
                letterSpacing = 5.sp,
            )
        }
    }
}
