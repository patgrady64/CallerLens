package com.pgdevhouse.callerlens.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pgdevhouse.callerlens.CallActivity

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> {
                CallSession.answer()
                context.startActivity(Intent(context, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            ACTION_REJECT -> CallSession.reject()
            ACTION_VOICEMAIL -> CallSession.sendToVoicemail()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.pgdevhouse.callerlens.action.ANSWER"
        const val ACTION_REJECT = "com.pgdevhouse.callerlens.action.REJECT"
        const val ACTION_VOICEMAIL = "com.pgdevhouse.callerlens.action.VOICEMAIL"
    }
}
