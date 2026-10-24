package com.rx8.dash.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.AppGraph
import com.rx8.dash.bt.BluetoothDevices
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.data.AppSettings
import com.rx8.dash.data.DashboardPalette
import com.rx8.dash.data.KnobAction
import com.rx8.dash.service.ServiceStarter
import com.rx8.dash.ui.components.LoadingBox
import com.rx8.dash.ui.knob.knobInput
import com.rx8.dash.ui.knob.rememberKnobAccumulator
import com.rx8.dash.ui.nav.AppTab
import com.rx8.dash.ui.nav.TabRail
import com.rx8.dash.ui.screens.DashboardScreen
import com.rx8.dash.ui.screens.DiagnosticsScreen
import com.rx8.dash.ui.screens.MaintenanceScreen
import com.rx8.dash.ui.screens.OnboardingScreen
import com.rx8.dash.ui.screens.RotaryHealthScreen
import com.rx8.dash.ui.screens.SettingsScreen
import com.rx8.dash.ui.screens.TripsScreen
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.theme.Rx8DashTheme
import com.rx8.dash.ui.vm.SessionViewModel

/**
 * Top-level composable. MainActivity calls this from setContent; the theme is applied here so the palette chosen in
 * Settings takes effect immediately.
 */
@Composable
fun Rx8DashApp(graph: AppGraph) {
    val session: SessionViewModel = viewModel(factory = SessionViewModel.Factory)
    val settings by session.settings.collectAsStateWithLifecycle()
    val s = settings
    Rx8DashTheme(palette = s?.palette ?: DashboardPalette.RENESIS_RED) {
        val c = LocalDashColors.current
        Surface(modifier = Modifier.fillMaxSize(), color = c.background) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                if (s == null) {
                    LoadingBox()
                } else {
                    AppContent(session = session, graph = graph, s = s)
                }
            }
        }
    }
}

@Composable
private fun AppContent(session: SessionViewModel, graph: AppGraph, s: AppSettings) {
    val context = LocalContext.current
    val status by session.status.collectAsStateWithLifecycle()
    val tabs = AppTab.values()

    var selectedIdx by rememberSaveable { mutableIntStateOf(0) }
    var highlightIdx by rememberSaveable { mutableIntStateOf(0) }
    var contentMode by rememberSaveable { mutableStateOf(false) }
    var onboardingDone by rememberSaveable { mutableStateOf(false) }

    // Keep the screen on while the adapter is connected.
    val connected = status.state == ConnectionState.CONNECTED
    val view = LocalView.current
    DisposableEffect(connected) {
        if (connected) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val needsOnboarding = !onboardingDone && !s.demoMode &&
        (!BluetoothDevices.hasConnectPermission(context) || s.deviceAddress == null)
    if (needsOnboarding) {
        OnboardingScreen(
            onContinue = {
                onboardingDone = true
                // No adapter chosen yet: send the user to Settings to pick one.
                if (s.deviceAddress == null) {
                    selectedIdx = AppTab.SETTINGS.ordinal
                    highlightIdx = selectedIdx
                }
                ServiceStarter.tryStart(context)
            },
            onDemo = {
                session.update { it.copy(demoMode = true) }
                onboardingDone = true
                ServiceStarter.tryStart(context)
            },
        )
        return
    }

    val focusManager = LocalFocusManager.current
    val rootRequester = remember { FocusRequester() }
    val accumulator = rememberKnobAccumulator()

    // Tab mode: the root holds focus and the knob browses tabs. Content mode: focus moves through the screen's controls.
    LaunchedEffect(contentMode) {
        try {
            if (contentMode) {
                // FocusDirection.Enter is experimental, so step to the first focusable control instead.
                focusManager.moveFocus(FocusDirection.Next)
            } else {
                focusManager.clearFocus()
                rootRequester.requestFocus()
            }
        } catch (e: IllegalStateException) {
            // Focus target not attached yet; touch input still works.
        }
        Unit
    }

    val tabCount = tabs.size
    Row(
        modifier = Modifier
            .fillMaxSize()
            .knobInput(s.keyMap, accumulator) { action, _ ->
                if (!contentMode) {
                    when (action) {
                        KnobAction.NEXT -> {
                            highlightIdx = (highlightIdx + 1) % tabCount
                            selectedIdx = highlightIdx
                            true
                        }
                        KnobAction.PREVIOUS -> {
                            highlightIdx = (highlightIdx + tabCount - 1) % tabCount
                            selectedIdx = highlightIdx
                            true
                        }
                        KnobAction.SELECT -> {
                            contentMode = true
                            true
                        }
                        KnobAction.BACK, KnobAction.NONE -> false
                    }
                } else {
                    when (action) {
                        KnobAction.NEXT -> {
                            focusManager.moveFocus(FocusDirection.Next)
                            true
                        }
                        KnobAction.PREVIOUS -> {
                            focusManager.moveFocus(FocusDirection.Previous)
                            true
                        }
                        // Let the focused control handle its own press.
                        KnobAction.SELECT -> false
                        KnobAction.BACK -> {
                            contentMode = false
                            true
                        }
                        KnobAction.NONE -> false
                    }
                }
            }
            .focusRequester(rootRequester)
            .focusable(),
    ) {
        TabRail(
            selected = tabs[selectedIdx],
            highlighted = tabs[highlightIdx],
            contentMode = contentMode,
            onSelect = { tab ->
                selectedIdx = tab.ordinal
                highlightIdx = tab.ordinal
                contentMode = false
            },
            // The rail is driven by the knob's own highlight state, not by the focus system.
            modifier = Modifier.focusProperties { canFocus = false },
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (tabs[selectedIdx]) {
                AppTab.DASHBOARD -> DashboardScreen(session = session, graph = graph)
                AppTab.HEALTH -> RotaryHealthScreen(session = session)
                AppTab.TRIPS -> TripsScreen(session = session)
                AppTab.DIAGNOSTICS -> DiagnosticsScreen(session = session)
                AppTab.MAINTENANCE -> MaintenanceScreen()
                AppTab.SETTINGS -> SettingsScreen(session = session)
            }
        }
    }
}
