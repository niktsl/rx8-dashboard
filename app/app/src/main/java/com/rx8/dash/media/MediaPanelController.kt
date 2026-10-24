package com.rx8.dash.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Notification-listener access as seen by the media panel. */
enum class MediaAccessState {
    /** The user has not granted notification access. */
    NO_ACCESS,

    /** Access is granted but the listener is not bound; a rebind was requested. */
    REBINDING,

    /** Listener bound; sessions are readable. */
    CONNECTED,

    /** Rebinding keeps failing: the UI should suggest re-toggling notification access. */
    REBIND_FAILED,
}

/** What is playing in the selected media session. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val packageName: String,
)

/** Pure watchdog decision, kept separate so it is unit-testable. */
object MediaWatchdog {
    const val MAX_REBIND_ATTEMPTS: Int = 3

    data class Decision(val state: MediaAccessState, val requestRebind: Boolean, val failedAttempts: Int)

    fun decide(
        accessEnabled: Boolean,
        listenerConnected: Boolean,
        failedAttempts: Int,
        maxAttempts: Int = MAX_REBIND_ATTEMPTS,
    ): Decision = when {
        !accessEnabled -> Decision(MediaAccessState.NO_ACCESS, requestRebind = false, failedAttempts = 0)
        listenerConnected -> Decision(MediaAccessState.CONNECTED, requestRebind = false, failedAttempts = 0)
        else -> {
            val attempts = failedAttempts + 1
            val state = if (attempts > maxAttempts) MediaAccessState.REBIND_FAILED else MediaAccessState.REBINDING
            Decision(state, requestRebind = true, failedAttempts = attempts)
        }
    }
}

/**
 * Now-playing and transport controls through MediaSessionManager.getActiveSessions(listener component), plus the
 * notification-listener rebind watchdog (every 30 s while started, and on wake).
 *
 * Call every public method on the main thread.
 */
class MediaPanelController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val component: ComponentName = MediaListenerService.component(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionManager: MediaSessionManager? =
        appContext.getSystemService(MediaSessionManager::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _accessState = MutableStateFlow(MediaAccessState.NO_ACCESS)
    val accessState: StateFlow<MediaAccessState> = _accessState.asStateFlow()

    private var watchdogJob: Job? = null
    private var connectionJob: Job? = null
    private var failedAttempts = 0

    private var sessionsListenerRegistered = false
    private var controller: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    /** Starts the watchdog and session tracking. Idempotent. */
    fun start() {
        if (watchdogJob?.isActive == true) return
        connectionJob = scope.launch {
            MediaListenerService.connected.collect { connected ->
                if (connected) {
                    failedAttempts = 0
                    _accessState.value = MediaAccessState.CONNECTED
                    attachSessions()
                } else {
                    detachSessions()
                    check()
                }
            }
        }
        watchdogJob = scope.launch {
            // The connection collector above already ran the first check.
            while (currentCoroutineContext().isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                check()
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        connectionJob?.cancel()
        connectionJob = null
        detachSessions()
    }

    /** Head-unit wake / app resume: run the watchdog now. */
    fun onWake() {
        check()
    }

    /** Runs one watchdog pass: detects missing access and requests a rebind when the listener is not bound. */
    fun check() {
        val enabled = try {
            NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)
        } catch (e: RuntimeException) {
            false
        }
        val connected = MediaListenerService.connected.value
        val decision = MediaWatchdog.decide(enabled, connected, failedAttempts)
        failedAttempts = decision.failedAttempts
        _accessState.value = decision.state
        if (decision.requestRebind) {
            try {
                NotificationListenerService.requestRebind(component)
            } catch (e: RuntimeException) {
                Log.w(TAG, "requestRebind failed", e)
            }
        }
        if (decision.state == MediaAccessState.CONNECTED && !sessionsListenerRegistered && watchdogJob?.isActive == true) attachSessions()
        if (decision.state == MediaAccessState.NO_ACCESS) detachSessions()
    }

    // ---- Transport ----

    fun play() {
        controller?.transportControls?.play()
    }

    fun pause() {
        controller?.transportControls?.pause()
    }

    fun playPause() {
        if (_nowPlaying.value?.isPlaying == true) pause() else play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    // ---- Sessions ----

    private fun attachSessions() {
        val msm = sessionManager ?: return
        try {
            if (!sessionsListenerRegistered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, mainHandler)
                sessionsListenerRegistered = true
            }
            onSessionsChanged(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Listener access not (yet) effective.
            Log.w(TAG, "No media session access", e)
            detachSessions()
        }
    }

    private fun refreshSessions() {
        val msm = sessionManager ?: return
        if (!sessionsListenerRegistered) return
        try {
            onSessionsChanged(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            detachSessions()
        }
    }

    private fun detachSessions() {
        if (sessionsListenerRegistered) {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: RuntimeException) {
                // Ignore.
            }
            sessionsListenerRegistered = false
        }
        selectController(null)
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        selectController(pick(controllers))
    }

    private fun pick(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null
        val spotify = controllers.filter { it.packageName == SPOTIFY_PACKAGE }
        return spotify.firstOrNull { isPlaying(it) }
            ?: controllers.firstOrNull { isPlaying(it) }
            ?: spotify.firstOrNull()
            ?: controllers.first()
    }

    private fun selectController(next: MediaController?) {
        val current = controller
        if (current != null && next != null && current.sessionToken == next.sessionToken) {
            publish()
            return
        }
        if (current != null) {
            try {
                current.unregisterCallback(controllerCallback)
            } catch (e: RuntimeException) {
                // Ignore.
            }
        }
        controller = next
        if (next != null) {
            try {
                next.registerCallback(controllerCallback, mainHandler)
            } catch (e: RuntimeException) {
                Log.w(TAG, "registerCallback failed", e)
            }
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _nowPlaying.value = null
            return
        }
        val md: MediaMetadata? = try {
            c.metadata
        } catch (e: RuntimeException) {
            null
        }
        _nowPlaying.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            albumArt = md?.let { artOf(it) },
            isPlaying = isPlaying(c),
            packageName = c.packageName,
        )
    }

    private fun artOf(md: MediaMetadata): Bitmap? =
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun isPlaying(c: MediaController): Boolean {
        val state = try {
            c.playbackState?.state
        } catch (e: RuntimeException) {
            null
        }
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    companion object {
        private const val TAG = "MediaPanel"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val WATCHDOG_INTERVAL_MS: Long = 30_000L
    }
}
