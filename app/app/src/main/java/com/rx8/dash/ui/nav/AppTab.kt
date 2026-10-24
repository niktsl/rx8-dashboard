package com.rx8.dash.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.ui.theme.LocalDashColors

/** Top-level destinations. Navigation is plain state (no navigation-compose). */
enum class AppTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dash", Icons.Filled.Speed),
    HEALTH("Rotary", Icons.Filled.Thermostat),
    TRIPS("Trips", Icons.Filled.Map),
    DIAGNOSTICS("Diag", Icons.Filled.BugReport),
    MAINTENANCE("Service", Icons.Filled.Build),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * Left-hand rail sized for a head unit: 88 dp wide, large targets. [highlighted] is the knob focus ring (tab mode);
 * [contentMode] dims the ring while the knob is moving through the screen's own controls.
 */
@Composable
fun TabRail(
    selected: AppTab,
    highlighted: AppTab,
    contentMode: Boolean,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalDashColors.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(c.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (tab in AppTab.values()) {
            val isSel = tab == selected
            val isHi = tab == highlighted && !contentMode
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(if (isSel) c.surfaceHigh else Color.Transparent, shape)
                    .border(
                        width = if (isHi) 3.dp else 0.dp,
                        color = if (isHi) c.accent else Color.Transparent,
                        shape = shape,
                    )
                    .clickable { onSelect(tab) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (isSel) c.accent else c.textDim,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = tab.label,
                    color = if (isSel) c.text else c.textDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
