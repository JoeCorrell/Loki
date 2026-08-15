package com.thor.launcher.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.thor.core.common.log.ThorLog
import com.thor.data.capture.RecordingState
import com.thor.data.capture.ScreenRecorder
import com.thor.launcher.LauncherActivity
import com.thor.launcher.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds a *screen* recording for as long as it runs, wherever the user goes.
 *
 * Only that kind. A recording of the launcher's own two panels is fed by the
 * launcher composing, and the launcher stops composing the moment it is not on
 * screen — so it cannot outlive the activity and a service that could buys nothing.
 * It also costs something real: from Android 14 a foreground service typed for media
 * projection is refused outright unless there is a projection to justify it, so
 * running the mock-up recording through here would have been rejected by the
 * platform. That one stays in the view model.
 *
 * A screen recording is the opposite case in every respect. This service owns the
 * encoder and compositor: MediaProjection supplies the primary panel and Loki's
 * explicitly enabled accessibility service supplies secondary-panel frames. It
 * therefore keeps capturing apps on either display after every launcher window is
 * covered, while its notification remains a reachable stop control.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var recorder: ScreenRecorder

    /** The screen's shape, read fresh so a rotation cannot leave it stale. */
    @Inject lateinit var geometry: RecordingGeometry

    private val notifications by lazy { NotificationManagerCompat.from(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ownsRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            recorder.state.collectLatest { state ->
                if (ownsRecording && state !is RecordingState.Active) finishService()
            }
        }
    }

    override fun onDestroy() {
        if (ownsRecording && recorder.isRecording) recorder.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCREEN -> startScreenRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> {
                // Nothing to do and nothing to show: a service with no work must not
                // sit in the foreground holding a notification about it.
                if (!recorder.isRecording) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * The real screen, through a consent result the activity has already collected.
     *
     * Order matters and is dictated by the platform: from Android 14 the foreground
     * service must already be running, and running with the media-projection type,
     * before `getMediaProjection` will hand anything over. Asking first throws.
     */
    private fun startScreenRecording(intent: Intent) {
        if (recorder.isRecording) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? =
            IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        if (data == null) {
            ThorLog.w(TAG, "Screen recording asked for with no consent result")
            stopSelf()
            return
        }

        goForeground(recording = true)

        val projection: MediaProjection? = runCatching {
            getSystemService(MediaProjectionManager::class.java)
                ?.getMediaProjection(resultCode, data)
        }.getOrNull()

        if (projection == null) {
            ThorLog.w(TAG, "The system would not grant a projection")
            stopRecording()
            return
        }

        // Resolve both physical displays before Loki can be covered by the app the
        // user is about to record. The compositor keeps this immutable layout.
        val layout = geometry.layout()
        val state = recorder.startProjection(
            projection = projection,
            requestedLayout = layout,
        )
        if (state is RecordingState.Failed) {
            ThorLog.w(TAG, "Screen recording refused: ${state.reason}")
            Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            stopRecording()
        } else if (state is RecordingState.Active) {
            ownsRecording = true
            if (!recorder.isRecording) finishService()
        }
    }

    private fun stopRecording() {
        val saved = runCatching { recorder.stop() }.getOrNull()
        ThorLog.i(TAG, saved?.let { "Saved $it" } ?: "Nothing was recorded")

        finishService()
    }

    private fun finishService() {
        ownsRecording = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(recording: Boolean) {
        val notification = buildNotification(recording)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The card in the shade, which is the whole point of the service.
     *
     * Ongoing and unswipeable while a recording runs: dismissing the notification
     * would leave the recording going with nothing to stop it, which is worse than a
     * notification that will not go away.
     */
    private fun buildNotification(recording: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(getString(R.string.recording_text))
            .setContentIntent(open)
            .setOngoing(recording)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                getString(R.string.recording_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel),
            // Low: this is a control surface, not news. It should be in the shade
            // when wanted and never in front of whatever is being recorded.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_summary)
            setShowBadge(false)
        }

        notifications.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START_SCREEN = "com.thor.launcher.RECORD_SCREEN"
        const val ACTION_STOP = "com.thor.launcher.RECORD_STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_CONSENT = "consent"

        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "loki.recording"
        private const val NOTIFICATION_ID = 4201

        /** Stops whatever is recording. */
        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
