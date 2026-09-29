package com.antivocale.app.service.live

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.antivocale.app.R
import com.antivocale.app.ui.live.LiveTranscriptionActivity
import com.antivocale.app.util.AppNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live mode: keeps a [LiveSessionController] session alive with the screen off
 * or the app in the background. Without it Android freezes the process (and
 * silences AudioRecord) as soon as the Activity leaves the foreground.
 *
 * - `microphone` foreground service type: the only way to keep capturing
 *   in the background (it must be STARTED while the app is visible, which the
 *   Start button guarantees).
 * - A partial wake lock for the session: decoding is CPU work that doze would
 *   otherwise stall with the screen off.
 * - The ongoing notification shows the last phrase and carries a Stop action.
 *
 * The service owns no audio logic: it starts the session, mirrors its state
 * into the notification, and stops itself once the session is no longer
 * running (after the tail phrases are decoded and saved).
 */
@AndroidEntryPoint
class LiveTranscriptionService : Service() {

    @Inject
    lateinit var controller: LiveSessionController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }
        AppNotificationChannel.INFERENCE.create(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(controller.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Mic permission missing or the app not visible (Android 14+ rule):
            // the session cannot run in the background, so do not start it.
            Log.e(TAG, "Microphone foreground service refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        controller.start()
        observe()
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            controller.state
                .map { NotificationKey(it.isRunning, it.status, it.segments.size, it.pendingPhrases) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (key, state) ->
                    if (!key.running) {
                        stopForegroundAndSelf()
                        return@collect
                    }
                    // Without POST_NOTIFICATIONS the service still runs; only the
                    // text refresh is skipped (the foreground post stays as is).
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            this@LiveTranscriptionService, android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching {
                            NotificationManagerCompat.from(this@LiveTranscriptionService)
                                .notify(NOTIFICATION_ID, buildNotification(state))
                        }
                    }
                }
        }
    }

    private data class NotificationKey(
        val running: Boolean,
        val status: LiveSessionController.Status,
        val phrases: Int,
        val pending: Int,
    )

    private fun buildNotification(state: LiveSessionController.LiveUiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, LiveTranscriptionActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveTranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val last = state.segments.lastOrNull()
        val text = last?.text ?: getString(
            if (state.status == LiveSessionController.Status.FINISHING) R.string.live_status_finishing
            else R.string.live_status_listening
        )
        val title = state.segments.takeIf { it.isNotEmpty() }?.let {
            getString(R.string.live_title) + " · " +
                resources.getQuantityString(R.plurals.live_notification_phrases, it.size, it.size)
        } ?: getString(R.string.live_title)
        return NotificationCompat.Builder(this, AppNotificationChannel.INFERENCE.id)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.live_stop), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiVocale:LiveDictation").apply {
            setReferenceCounted(false)
            // Upper bound so a leaked session can never pin the CPU forever.
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveTranscriptionSvc"
        private const val ACTION_STOP = "com.antivocale.app.live.STOP"
        private const val WAKE_LOCK_MAX_MS = 6L * 60 * 60 * 1000

        /** Fixed id below the result allocator's base (reserved-range contract). */
        const val NOTIFICATION_ID = 1007

        /** Starts a live session as a microphone foreground service (call while visible). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveTranscriptionService::class.java))
        }
    }
}
