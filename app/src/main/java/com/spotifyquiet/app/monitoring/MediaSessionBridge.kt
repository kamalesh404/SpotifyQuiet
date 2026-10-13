package com.spotifyquiet.app.monitoring

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.spotifyquiet.app.analysis.PlaybackPhase
import com.spotifyquiet.app.analysis.PlaybackSnapshot
import com.spotifyquiet.app.analysis.SnapshotSource
import com.spotifyquiet.app.data.EventKind

/**
 * Thin wrapper around [MediaSessionManager].
 *
 * Requires notification access (Android allows enabled notification listeners
 * to enumerate active media sessions). Listeners are registered once while
 * monitoring is on and fully released when it stops; there is no polling.
 */
class MediaSessionBridge(
    private val context: Context,
    private val listenerComponent: ComponentName,
    private val onSessionListChanged: (connected: Boolean) -> Unit,
    private val onSnapshot: (kind: EventKind, snapshot: PlaybackSnapshot) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val attached = LinkedHashMap<MediaController, MediaController.Callback>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachControllers(controllers)
        }

    private var started = false

    val isStarted: Boolean get() = started

    val connectedPackage: String?
        get() = attached.keys.firstOrNull()?.packageName

    fun start() {
        if (started) return
        val manager = sessionManager
        if (manager == null) {
            onError("MediaSessionManager is unavailable on this device")
            onSessionListChanged(false)
            return
        }
        started = true
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            attachControllers(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            started = false
            onError("Notification access is required to read Spotify's media session")
            onSessionListChanged(false)
        } catch (e: RuntimeException) {
            started = false
            onError("Could not register media session listener: ${e.message}")
            onSessionListChanged(false)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        detachAll()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: RuntimeException) {
            // Removing an already-removed listener is not fatal.
        }
        onSessionListChanged(false)
    }

    private fun attachControllers(controllers: List<MediaController>?) {
        val list = controllers.orEmpty().filter { it.packageName == SPOTIFY_PACKAGE }
        val existing = attached.keys.toList()

        for (controller in existing) {
            if (list.none { it.sessionToken == controller.sessionToken }) {
                detach(controller)
            }
        }
        for (controller in list) {
            if (attached.keys.none { it.sessionToken == controller.sessionToken }) {
                attach(controller)
            }
        }
        onSessionListChanged(attached.isNotEmpty())
    }

    private fun attach(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                emit(controller, EventKind.MEDIA_STATE)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emit(controller, EventKind.MEDIA_METADATA)
            }

            override fun onSessionDestroyed() {
                detach(controller)
                onSessionListChanged(attached.isNotEmpty())
            }
        }
        attached[controller] = callback
        try {
            controller.registerCallback(callback)
            emit(controller, EventKind.SESSION_APPEARED)
        } catch (e: RuntimeException) {
            attached.remove(controller)
            onError("Could not observe Spotify's media session: ${e.message}")
        }
    }

    private fun detach(controller: MediaController) {
        val callback = attached.remove(controller) ?: return
        try {
            controller.unregisterCallback(callback)
        } catch (e: RuntimeException) {
            // The session may already be gone.
        }
    }

    private fun detachAll() {
        val controllers = attached.keys.toList()
        for (controller in controllers) {
            detach(controller)
        }
        attached.clear()
    }

    private fun emit(controller: MediaController, kind: EventKind) {
        val snapshot = snapshotOf(controller) ?: return
        onSnapshot(kind, snapshot)
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        fun snapshotOf(controller: MediaController): PlaybackSnapshot? {
            val metadata = controller.metadata
            val state = controller.playbackState
            if (metadata == null && state == null) return null
            return PlaybackSnapshot(
                timestampMs = System.currentTimeMillis(),
                source = SnapshotSource.MEDIA_SESSION,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata
                    ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    ?.takeIf { it > 0L },
                positionMs = state?.position?.takeIf { it >= 0L },
                phase = phaseOf(state),
                packageName = controller.packageName,
            )
        }

        fun phaseOf(state: PlaybackState?): PlaybackPhase = when (state?.state) {
            PlaybackState.STATE_PLAYING -> PlaybackPhase.PLAYING
            PlaybackState.STATE_PAUSED -> PlaybackPhase.PAUSED
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            -> PlaybackPhase.BUFFERING
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            -> PlaybackPhase.STOPPED
            PlaybackState.STATE_ERROR -> PlaybackPhase.ERROR
            else -> PlaybackPhase.UNKNOWN
        }
    }
}
