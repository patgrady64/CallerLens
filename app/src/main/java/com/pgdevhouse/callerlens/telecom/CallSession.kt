package com.pgdevhouse.callerlens.telecom

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.BlockedNumberContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.pgdevhouse.callerlens.data.isUsablePhoneNumber
import kotlinx.coroutines.flow.asStateFlow


data class CallUiState(
    val hasCall: Boolean = false,
    val number: String = "",
    val displayName: String? = null,
    val numberPresentation: Int = TelecomManager.PRESENTATION_UNKNOWN,
    val state: Int = Call.STATE_DISCONNECTED,
    val isIncoming: Boolean = true,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false
)

object CallSession {
    private var currentCall: Call? = null
    private var service: CallerLensInCallService? = null

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED) {
                clearActiveCall(call)
            } else {
                refresh(call)
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            refresh(call)
        }
    }

    fun attach(service: CallerLensInCallService, call: Call) {
        currentCall?.let { oldCall ->
            runCatching { oldCall.unregisterCallback(callback) }
        }
        this.service = service
        currentCall = call
        call.registerCallback(callback)
        refresh(call)
    }

    fun detach(call: Call) {
        runCatching { call.unregisterCallback(callback) }
        clearActiveCall(call)
    }

    fun updateAudioState(audioState: CallAudioState?) {
        val current = _uiState.value
        _uiState.value = current.copy(
            isMuted = audioState?.isMuted ?: current.isMuted,
            isSpeaker = audioState?.route == CallAudioState.ROUTE_SPEAKER
        )
    }

    fun answer() {
        val call = currentCall ?: return
        if (callState(call) == Call.STATE_RINGING || callState(call) == Call.STATE_SIMULATED_RINGING) {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
    }

    fun sendToVoicemail() {
        val call = currentCall ?: return
        if (callState(call) == Call.STATE_RINGING || callState(call) == Call.STATE_SIMULATED_RINGING) {
            // A rejected cellular call is normally routed to carrier voicemail when voicemail is configured.
            // Android does not provide a universal "send this PSTN call directly to voicemail" API.
            call.reject(false, null)
        }
    }

    fun reject() {
        val call = currentCall ?: return
        if (callState(call) == Call.STATE_RINGING || callState(call) == Call.STATE_SIMULATED_RINGING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_DECLINED)
            } else {
                call.reject(false, null)
            }
        } else {
            call.disconnect()
        }
    }

    fun hangUp() {
        currentCall?.disconnect()
    }

    fun toggleMute() {
        val svc = service ?: return
        val muted = !_uiState.value.isMuted
        svc.setMuted(muted)
        _uiState.value = _uiState.value.copy(isMuted = muted)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val svc = service ?: return
        val speakerOn = _uiState.value.isSpeaker
        svc.setAudioRoute(if (speakerOn) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        _uiState.value = _uiState.value.copy(isSpeaker = !speakerOn)
    }

    fun blockNumber(context: Context): Boolean {
        val current = _uiState.value
        val number = current.number
        if (!isUsablePhoneNumber(number, current.numberPresentation)) return false
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return false

        return try {
            if (!BlockedNumberContract.isBlocked(context, number)) {
                val values = ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                }
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
            }
            rejectAsUnwanted()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun rejectAsUnwanted() {
        val call = currentCall ?: return
        if (callState(call) == Call.STATE_RINGING || callState(call) == Call.STATE_SIMULATED_RINGING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_UNWANTED)
            } else {
                call.reject(false, null)
            }
        }
    }

    private fun clearActiveCall(call: Call) {
        if (currentCall === call) {
            currentCall = null
            service = null
            _uiState.value = CallUiState()
        }
    }

    private fun refresh(call: Call) {
        val state = callState(call)
        if (state == Call.STATE_DISCONNECTED) {
            clearActiveCall(call)
            return
        }

        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val contactName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) details.contactDisplayName else null
        val networkName = details.callerDisplayName
        val presentation = details.handlePresentation
        val direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) details.callDirection else Call.Details.DIRECTION_UNKNOWN

        _uiState.value = _uiState.value.copy(
            hasCall = true,
            number = number,
            displayName = contactName?.takeIf { it.isNotBlank() }
                ?: networkName?.takeIf { it.isNotBlank() },
            numberPresentation = presentation,
            state = state,
            isIncoming = direction != Call.Details.DIRECTION_OUTGOING
        )
    }

    @Suppress("DEPRECATION")
    private fun callState(call: Call): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state
}
