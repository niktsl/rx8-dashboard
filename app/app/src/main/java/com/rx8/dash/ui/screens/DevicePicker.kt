package com.rx8.dash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.ui.components.focusRing
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.BondedDevice

/** Lists already-paired Bluetooth devices; the OBD adapter must be paired in Android settings first. */
@Composable
fun DevicePickerDialog(
    devices: List<BondedDevice>,
    selectedAddress: String?,
    onRefresh: () -> Unit,
    onPick: (BondedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalDashColors.current
    LaunchedEffect(Unit) { onRefresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose OBD adapter") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) {
                    Text(
                        "No paired devices found. Pair the ELM327 adapter in Android Bluetooth settings first " +
                            "(the PIN is usually 1234 or 0000), then reopen this list.",
                        color = c.textDim,
                    )
                }
                for (d in devices) {
                    val selected = d.address == selectedAddress
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .focusRing(RoundedCornerShape(10.dp))
                            .clickable { onPick(d) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                d.name,
                                color = if (selected) c.accent else c.text,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 18.sp,
                            )
                            Text(d.address, color = c.textDim, fontSize = 14.sp)
                        }
                        if (selected) Text("SELECTED", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        },
    )
}
