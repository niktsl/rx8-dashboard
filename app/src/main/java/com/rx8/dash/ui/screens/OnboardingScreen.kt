package com.rx8.dash.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.StatusPill
import com.rx8.dash.ui.theme.LocalDashColors

/** Runtime state of each onboarding item, re-read whenever the screen resumes. */
private data class PermissionState(
    val bluetooth: Boolean,
    val notifications: Boolean,
    val listener: Boolean,
    val battery: Boolean,
)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun readState(context: Context): PermissionState {
    val bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (granted(context, Manifest.permission.BLUETOOTH_CONNECT) && granted(context, Manifest.permission.BLUETOOTH_SCAN))
    val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        granted(context, Manifest.permission.POST_NOTIFICATIONS)
    val listener = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: SecurityException) {
        false
    }
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val battery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    return PermissionState(bluetooth, notifications, listener, battery)
}

private fun launchSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some head units ship without this settings page; the row stays "not granted".
    } catch (e: SecurityException) {
        // Same: nothing else to do.
    }
}

/**
 * First-run screen. Each row shows its status and a button. [onContinue] needs the Bluetooth permission;
 * [onDemo] lets the user look around without an adapter.
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Reading `refresh` makes this recompute after every resume or permission result.
    val state = remember(refresh) { readState(context) }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh += 1
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh += 1
    }

    ScreenColumn(title = "Set up RX-8 Dash", modifier = modifier) {
        Text(
            "Grant what the app needs to talk to the OBD adapter and run in the background. " +
                "Only Bluetooth is required; the rest improve reliability.",
            color = LocalDashColors.current.textDim,
            fontSize = 16.sp,
        )

        val btText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Nearby devices (Bluetooth connect and scan) so the app can open the paired OBD adapter."
        } else {
            "Bluetooth is granted at install on this Android version."
        }
        PermissionRow(
            title = "Bluetooth",
            description = btText,
            ok = state.bluetooth,
            buttonText = "Grant",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothLauncher.launch(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    )
                }
            },
        )

        PermissionRow(
            title = "Notifications",
            description = "Needed on Android 13+ to show the persistent connection notification while logging.",
            ok = state.notifications,
            buttonText = "Grant",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        PermissionRow(
            title = "Notification access (media panel)",
            description = "Lets the dashboard show now-playing info and control music. Enable RX8 Dash in the list.",
            ok = state.listener,
            buttonText = "Open settings",
            onClick = { launchSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )

        PermissionRow(
            title = "Battery optimization",
            description = "Exempting the app lets it reconnect after the head unit wakes from standby.",
            ok = state.battery,
            buttonText = "Allow",
            onClick = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName),
                )
                launchSettings(context, intent)
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton(
                text = "Continue",
                onClick = onContinue,
                enabled = state.bluetooth,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                text = "Continue in demo mode",
                onClick = onDemo,
                outlined = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (!state.bluetooth) {
            Text(
                "Grant Bluetooth to continue with a real adapter, or use demo mode with simulated data.",
                color = LocalDashColors.current.textDim,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    ok: Boolean,
    buttonText: String,
    onClick: () -> Unit,
) {
    val c = LocalDashColors.current
    DashCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(description, color = c.textDim, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(
                text = if (ok) "GRANTED" else "NOT SET",
                color = if (ok) c.ok else c.caution,
            )
            Spacer(Modifier.width(12.dp))
            BigButton(
                text = buttonText,
                onClick = onClick,
                enabled = !ok,
                outlined = true,
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}
