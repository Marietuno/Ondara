package com.metrolist.android.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Needed only so Android lets the app see and control other media sessions, such as the head unit's FM/AM radio.
class TunerListenerService : NotificationListenerService()

data class CarTunerState(
    val hasAccess: Boolean = false,
    val tunerPackage: String? = null,
    val tunerLabel: String? = null,
    val connected: Boolean = false,
    val title: String? = null,
    val subtitle: String? = null,
    val isPlaying: Boolean = false,
)

/**
 * Controls the car head unit's own FM/AM radio app through its media session, which works on any unit
 * whose radio app publishes one (most Android head units do). Seek and preset changes map to next/previous.
 */
class CarTuner(
    context: Context,
) {
    private val context = context.applicationContext
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, TunerListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarTunerState())
    val state: StateFlow<CarTunerState> = _state.asStateFlow()
    private var controller: MediaController? = null

    private val callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

            override fun onSessionDestroyed() {
                detach()
                publish()
            }
        }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers.orEmpty())
        }

    fun start() {
        val hasAccess = hasAccess()
        val tuner = findTunerPackage()
        _state.value =
            CarTunerState(
                hasAccess = hasAccess,
                tunerPackage = tuner,
                tunerLabel = tuner?.let(::labelOf),
            )
        if (!hasAccess) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            attach(sessionManager.getActiveSessions(listenerComponent))
        }
    }

    fun stop() {
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        detach()
    }

    fun accessSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openTunerApp() {
        val pkg = _state.value.tunerPackage ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun seekUp() {
        controller?.transportControls?.skipToNext() ?: openTunerApp()
    }

    fun seekDown() {
        controller?.transportControls?.skipToPrevious() ?: openTunerApp()
    }

    fun togglePlay() {
        val current = controller ?: return openTunerApp()
        if (current.playbackState?.state == PlaybackState.STATE_PLAYING) {
            current.transportControls.pause()
        } else {
            current.transportControls.play()
        }
    }

    // Radio apps that implement search accept a frequency such as "98.5" or "1080"; others ignore it.
    fun tuneTo(frequency: String) {
        val text = frequency.trim().replace(',', '.')
        if (text.isEmpty()) return
        controller?.transportControls?.playFromSearch(text, null)
    }

    private fun attach(controllers: List<MediaController>) {
        val tuner = _state.value.tunerPackage
        val match =
            controllers.firstOrNull { it.packageName == tuner }
                ?: controllers.firstOrNull { isTunerPackage(it.packageName) }
        if (match?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        detach()
        controller = match?.also { it.registerCallback(callback, handler) }
        if (match != null && _state.value.tunerPackage != match.packageName) {
            _state.value = _state.value.copy(tunerPackage = match.packageName, tunerLabel = labelOf(match.packageName))
        }
        publish()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun publish() {
        val current = controller
        val metadata = current?.metadata
        _state.value =
            _state.value.copy(
                connected = current != null,
                title =
                    metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                subtitle =
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                isPlaying = current?.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
    }

    private fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    private fun labelOf(pkg: String): String? =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()

    // Head units ship the tuner as a preinstalled app; prefer known tuner packages, then any system "radio"/"fm" app.
    private fun findTunerPackage(): String? {
        val pm = context.packageManager
        val launchable =
            pm
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
        launchable.firstOrNull { it.packageName in KNOWN_TUNERS }?.let { return it.packageName }
        return launchable
            .filter { isTunerPackage(it.packageName) }
            .map { it.packageName }
            .firstOrNull()
    }

    private fun isTunerPackage(pkg: String): Boolean {
        if (pkg == context.packageName) return false
        if (pkg in KNOWN_TUNERS) return true
        val name = pkg.lowercase()
        // Name hints only count for preinstalled apps, so internet radio apps are not mistaken for the tuner.
        return TUNER_HINTS.any { it in name } && isSystemApp(pkg)
    }

    private fun isSystemApp(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)

    companion object {
        // Tuner apps of common head-unit platforms (FYT/SYU, Microntek, TopWay/TS, AOSP/Qualcomm/MediaTek, AAOS).
        private val KNOWN_TUNERS =
            setOf(
                "com.syu.radio",
                "com.microntek.radio",
                "com.ts.radio",
                "com.hzbhd.radio",
                "com.android.car.radio",
                "com.android.fmradio",
                "com.caf.fmradio",
                "com.mediatek.fmradio",
                "com.spreadtrum.fmradio",
                "com.sprd.fmradio",
            )
        private val TUNER_HINTS = listOf("fmradio", ".radio", "radio.", "tuner")
    }
}
