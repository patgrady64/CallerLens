package com.pgdevhouse.callerlens.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.pgdevhouse.callerlens.CallActivity
import com.pgdevhouse.callerlens.R
import com.pgdevhouse.callerlens.data.CallLogRepository
import com.pgdevhouse.callerlens.data.NumberStats
import com.pgdevhouse.callerlens.data.fallbackCallerName
import com.pgdevhouse.callerlens.data.formatPhoneNumber
import com.pgdevhouse.callerlens.data.isUsablePhoneNumber
import com.pgdevhouse.callerlens.data.normalizedPhoneKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallerLensInCallService : InCallService() {

    private val notificationCallbacks = mutableMapOf<Call, Call.Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val statsCache = mutableMapOf<String, NumberStats>()
    private val statsLoadsInFlight = mutableSetOf<String>()

    private val notificationWatchdog = object : Runnable {
        override fun run() {
            val hasLiveCall = notificationCallbacks.keys.any { call ->
                runCatching { currentState(call) != Call.STATE_DISCONNECTED }.getOrDefault(false)
            }

            if (!hasLiveCall) {
                cancelCallNotification()
                return
            }

            mainHandler.postDelayed(this, NOTIFICATION_WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createCallChannel()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallSession.attach(this, call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED) {
                    cancelCallNotification()
                } else {
                    showCallNotification(call)
                    if (state == Call.STATE_ACTIVE ||
                        state == Call.STATE_HOLDING ||
                        state == Call.STATE_DIALING ||
                        state == Call.STATE_CONNECTING
                    ) {
                        bringCallUiToFront()
                    }
                }
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                showCallNotification(call)
            }

            override fun onCallDestroyed(call: Call) {
                notificationCallbacks.remove(call)?.let { callback ->
                    runCatching { call.unregisterCallback(callback) }
                }
                CallSession.detach(call)
                if (notificationCallbacks.isEmpty()) {
                    cancelCallNotification()
                }
            }
        }
        notificationCallbacks[call] = callback
        call.registerCallback(callback)
        showCallNotification(call)

        // Android 13+ intentionally uses a heads-up notification while the
        // device is in use. For non-ringing calls, bring the ongoing-call UI
        // forward immediately. Ringing calls use the notification/full-screen
        // intent path until the user answers or opens the call UI.
        val initialState = currentState(call)
        if (initialState != Call.STATE_RINGING && initialState != Call.STATE_SIMULATED_RINGING) {
            bringCallUiToFront()
        }
    }

    override fun onCallRemoved(call: Call) {
        notificationCallbacks.remove(call)?.let(call::unregisterCallback)
        CallSession.detach(call)
        cancelCallNotification()
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationWatchdog)
        notificationCallbacks.forEach { (call, callback) ->
            runCatching { call.unregisterCallback(callback) }
        }
        notificationCallbacks.clear()
        serviceScope.cancel()
        statsCache.clear()
        statsLoadsInFlight.clear()
        cancelCallNotification()
        super.onDestroy()
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        bringCallUiToFront()
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        CallSession.updateAudioState(audioState)
    }

    private fun showCallNotification(call: Call) {
        val state = currentState(call)
        if (state == Call.STATE_DISCONNECTED) {
            cancelCallNotification()
            return
        }

        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val presentation = details.handlePresentation
        val usableNumber = isUsablePhoneNumber(number, presentation)
        val numberKey = if (usableNumber) normalizedPhoneKey(number) else null
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            details.contactDisplayName?.takeIf { it.isNotBlank() }
        } else null
        val display = name
            ?: details.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: if (usableNumber) formatPhoneNumber(number) else fallbackCallerName(presentation)
        val isIncoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val isRinging = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING
        val stats = numberKey?.let(statsCache::get)

        if (isIncoming && isRinging && numberKey != null && stats == null) {
            loadRecentStats(call, number, numberKey)
        }

        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            10,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = actionPendingIntent(CallActionReceiver.ACTION_ANSWER, 11)
        val voicemailIntent = actionPendingIntent(CallActionReceiver.ACTION_VOICEMAIL, 12)
        val rejectIntent = actionPendingIntent(CallActionReceiver.ACTION_REJECT, 13)

        val frequencyHeadline = if (isIncoming && isRinging && stats != null) {
            val liveCount = stats.total + 1
            "$liveCount ${if (liveCount == 1) "call" else "calls"} in the last 7 days"
        } else null

        // Keep the caller identity on line 1 by itself. The frequency starts on
        // line 2 so it does not get clipped beside a long caller name/number.
        // The history detail is requested on line 3; compact OEM heads-up layouts
        // may hide that third line, but the frequency line remains visible.
        val notificationBody = when {
            isIncoming && isRinging && !usableNumber -> "Recent call history unavailable"
            isIncoming && isRinging && stats == null -> "Checking recent call history…"
            isIncoming && isRinging && stats != null ->
                "$frequencyHeadline\n${buildRecentHistoryText(stats)}"
            state == Call.STATE_ACTIVE -> "Call in progress"
            state == Call.STATE_HOLDING -> "Call on hold"
            usableNumber -> formatPhoneNumber(number)
            else -> fallbackCallerName(presentation)
        }

        val person = Person.Builder().setName(display).build()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caller_lens)
            .setContentTitle(display)
            .setContentText(notificationBody)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(fullScreenPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (name != null && usableNumber) {
            builder.setSubText(formatPhoneNumber(number))
        }

        if (isRinging) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isRinging) {
                // Incoming CallStyle is valid because this notification has a full-screen intent.
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(person, rejectIntent, answerIntent)
                )
            } else {
                // CallerLensInCallService is bound by Telecom, but it is not a foreground
                // service. Android rejects CallStyle.forOngoingCall() in that situation
                // unless the notification also uses a full-screen intent. A normal ongoing
                // notification is sufficient here and avoids crashing as soon as the call
                // becomes active.
                builder.addAction(0, "Hang up", rejectIntent)
            }
        } else {
            if (state == Call.STATE_RINGING) {
                builder.addAction(0, "Answer", answerIntent)
                builder.addAction(0, "Voicemail", voicemailIntent)
                builder.addAction(0, "Reject", rejectIntent)
            } else {
                builder.addAction(0, "Hang up", rejectIntent)
            }
        }

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
        startNotificationWatchdog()
    }

    private fun loadRecentStats(call: Call, number: String, numberKey: String) {
        if (!statsLoadsInFlight.add(numberKey)) return

        serviceScope.launch {
            val stats = runCatching {
                CallLogRepository(this@CallerLensInCallService).statsFor(number, RECENT_HISTORY_DAYS)
            }.getOrDefault(NumberStats(days = RECENT_HISTORY_DAYS))

            statsLoadsInFlight.remove(numberKey)
            statsCache[numberKey] = stats

            val stillLive = notificationCallbacks.containsKey(call) &&
                runCatching { currentState(call) != Call.STATE_DISCONNECTED }.getOrDefault(false)
            if (stillLive) {
                showCallNotification(call)
            }
        }
    }

    private fun buildRecentHistoryText(stats: NumberStats): String {
        val last = stats.lastCallMillis?.let { "Last: ${formatRelativeCallTime(it)}" } ?: "No earlier calls in 7 days"
        val breakdown = buildList {
            if (stats.missed > 0) add("${stats.missed} missed")
            if (stats.rejected > 0) add("${stats.rejected} rejected")
            if (stats.answered > 0) add("${stats.answered} answered")
            if (stats.blocked > 0) add("${stats.blocked} blocked")
        }.joinToString(" · ")

        return if (breakdown.isBlank()) last else "$last · $breakdown"
    }

    private fun formatRelativeCallTime(timeMillis: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timeMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    private fun startNotificationWatchdog() {
        mainHandler.removeCallbacks(notificationWatchdog)
        mainHandler.postDelayed(notificationWatchdog, NOTIFICATION_WATCHDOG_MS)
    }

    private fun cancelCallNotification() {
        mainHandler.removeCallbacks(notificationWatchdog)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun bringCallUiToFront() {
        startActivity(Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun createCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming and ongoing calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "CallerLens call controls"
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentState(call: Call): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state

    companion object {
        private const val CHANNEL_ID = "caller_lens_calls"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_WATCHDOG_MS = 1_000L
        private const val RECENT_HISTORY_DAYS = 7
    }
}
