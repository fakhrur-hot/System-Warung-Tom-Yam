package com.razstudio.pos.realtime

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.razstudio.pos.data.local.LocalPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the configurable "new order" alert.
 *
 * Why the app plays this itself instead of letting the notification channel do it:
 * a [android.app.NotificationChannel]'s sound is **immutable after the channel is created** — later
 * `createNotificationChannel` calls silently ignore sound changes — and a channel offers no volume
 * control whatsoever (its sound always plays at the device's notification-stream level). Since the
 * café needs to pick both the ringtone AND how loud it is, the new-order channel is created silent
 * (see [RealtimeService.createNotificationChannel]) and this class owns the sound.
 *
 * [MediaPlayer] rather than [android.media.Ringtone] because `Ringtone.setVolume` only exists on
 * API 28+, and this app supports API 26.
 */
@Singleton
class NewOrderSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localPrefs: LocalPrefs,
) {
    companion object {
        private const val TAG = "NewOrderSound"

        /**
         * A single catch-up poll can surface several new orders at once, and one alert per order
         * would start overlapping players — a garbled burst rather than a ping. Collapse alerts
         * that land within this window into one.
         */
        private const val MIN_INTERVAL_MS = 800L
    }

    @Volatile
    private var lastPlayedAt = 0L

    /**
     * The alert URI to use: the operator's explicit pick, or the system notification default when
     * they've never chosen. Returns null for "Silent" (an explicit blank choice).
     */
    fun currentUri(): Uri? {
        if (!localPrefs.newOrderSoundChosen) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        val stored = localPrefs.newOrderSoundUri
        return if (stored.isNullOrBlank()) null else Uri.parse(stored)
    }

    /** Human-readable name of the current alert, for the settings row. Null when silent. */
    fun currentTitle(): String? = try {
        currentUri()?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve ringtone title", e)
        null
    }

    /** Persist the operator's pick. Pass null for "Silent". */
    fun setUri(uri: Uri?) {
        localPrefs.newOrderSoundUri = uri?.toString() ?: ""
    }

    fun volumePercent(): Int = localPrefs.newOrderSoundVolume

    fun setVolumePercent(percent: Int) {
        localPrefs.newOrderSoundVolume = percent
    }

    /**
     * Play the alert. Safe to call from any thread; no-ops when set to Silent, at zero volume, or
     * within [MIN_INTERVAL_MS] of the previous alert.
     *
     * @param respectThrottle false for the settings "Test" button, so repeated taps always sound.
     */
    fun play(respectThrottle: Boolean = true) {
        val uri = currentUri() ?: return
        val volume = localPrefs.newOrderSoundVolume / 100f
        if (volume <= 0f) return

        val now = System.currentTimeMillis()
        if (respectThrottle && now - lastPlayedAt < MIN_INTERVAL_MS) return
        lastPlayedAt = now

        try {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.setVolume(volume, volume)
            // Release from the player's own callbacks — a leaked MediaPlayer holds an audio focus
            // handle and a codec, and this fires on every order all service long.
            player.setOnCompletionListener { it.safeRelease() }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.safeRelease()
                true
            }
            // prepareAsync so a slow content resolve can never block a caller (the service posts
            // this from its IO scope, the Test button from the main thread).
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play new-order alert", e)
        }
    }

    private fun MediaPlayer.safeRelease() {
        try {
            reset()
            release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer release failed", e)
        }
    }
}
