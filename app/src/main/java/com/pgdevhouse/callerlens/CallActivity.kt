package com.pgdevhouse.callerlens

import android.os.Bundle
import android.telecom.Call
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgdevhouse.callerlens.data.CallLogRepository
import com.pgdevhouse.callerlens.data.NumberStats
import com.pgdevhouse.callerlens.data.formatLastCall
import com.pgdevhouse.callerlens.data.formatPhoneNumber
import com.pgdevhouse.callerlens.telecom.CallSession
import com.pgdevhouse.callerlens.telecom.CallUiState
import com.pgdevhouse.callerlens.ui.theme.CallerLensTheme

private const val SETTINGS_PREFS = "caller_lens_settings"
private const val KEEP_SCREEN_ON_KEY = "keep_screen_on_during_call"

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        applyKeepScreenOn(
            getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getBoolean(KEEP_SCREEN_ON_KEY, false)
        )

        setContent {
            CallerLensTheme(darkTheme = true) {
                val state by CallSession.uiState.collectAsState()
                LaunchedEffect(state.hasCall) {
                    if (!state.hasCall) finishAndRemoveTask()
                }
                CallScreen(state)
            }
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun saveKeepScreenOn(enabled: Boolean) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEEP_SCREEN_ON_KEY, enabled)
            .apply()
        applyKeepScreenOn(enabled)
    }

    @Composable
    private fun CallScreen(state: CallUiState) {
        var stats by remember(state.number) { mutableStateOf(NumberStats(days = 7)) }
        var statsLoaded by remember(state.number) { mutableStateOf(false) }
        var keepScreenOn by remember {
            mutableStateOf(
                getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                    .getBoolean(KEEP_SCREEN_ON_KEY, false)
            )
        }

        LaunchedEffect(state.number) {
            statsLoaded = false
            if (state.number.isNotBlank()) {
                stats = runCatching {
                    CallLogRepository(this@CallActivity).statsFor(state.number, 7)
                }.getOrDefault(NumberStats(days = 7))
            }
            statsLoaded = true
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                DecorativeBackdrop()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CallScreenHeader(state)
                    Spacer(Modifier.height(20.dp))
                    CallerIdentity(state)
                    Spacer(Modifier.height(16.dp))

                    when (state.state) {
                        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> {
                            IncomingFrequencyHero(stats, statsLoaded)
                            Spacer(Modifier.height(12.dp))
                            KeepScreenOnToggle(
                                checked = keepScreenOn,
                                onCheckedChange = { enabled ->
                                    keepScreenOn = enabled
                                    saveKeepScreenOn(enabled)
                                }
                            )
                            Spacer(Modifier.height(14.dp))
                            IncomingCallContent()
                        }
                        Call.STATE_ACTIVE, Call.STATE_HOLDING -> {
                            KeepScreenOnToggle(
                                checked = keepScreenOn,
                                onCheckedChange = { enabled ->
                                    keepScreenOn = enabled
                                    saveKeepScreenOn(enabled)
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                            ActiveCallContent(state, stats)
                        }
                        Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                            KeepScreenOnToggle(
                                checked = keepScreenOn,
                                onCheckedChange = { enabled ->
                                    keepScreenOn = enabled
                                    saveKeepScreenOn(enabled)
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                            DialingCallContent()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    @Composable
    private fun DecorativeBackdrop() {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(230.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                        CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(180.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        CircleShape
                    )
            )
        }
    }

    @Composable
    private fun CallScreenHeader(state: CallUiState) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "CALLERLENS",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.8.sp
                )
                Text(
                    text = if (state.isIncoming) "Incoming call" else "Phone call",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            StatusPill(state)
        }
    }

    @Composable
    private fun CallerIdentity(state: CallUiState) {
        val initial = state.displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.first()
            ?.uppercaseChar()
            ?.toString()
            ?: "?"

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier
                    .size(112.dp)
                    .border(
                        BorderStroke(4.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                        CircleShape
                    ),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initial,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = state.displayName ?: "Unknown Caller",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatPhoneNumber(state.number),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
        }
    }

    @Composable
    private fun KeepScreenOnToggle(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSecondary,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keep screen on",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (checked) "Display stays awake for this call" else "Use the normal screen timeout",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.background,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }

    @Composable
    private fun StatusPill(state: CallUiState) {
        val label = when (state.state) {
            Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "CONNECTED"
            Call.STATE_HOLDING -> "ON HOLD"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "CONNECTING"
            else -> "CALL"
        }

        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(999.dp),
            shadowElevation = 2.dp
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }

    @Composable
    private fun IncomingFrequencyHero(stats: NumberStats, loaded: Boolean) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(26.dp),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = "RECENT CALL FREQUENCY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(8.dp))

                if (!loaded) {
                    Text(
                        text = "Checking recent call history…",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    val callsIncludingThisOne = stats.total + 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = callsIncludingThisOne.toString(),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 50.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Column {
                            Text(
                                text = if (callsIncludingThisOne == 1)
                                    "call in the last ${stats.days} days"
                                else
                                    "calls in the last ${stats.days} days",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "including this incoming call",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (stats.total == 0)
                            "No previous calls from this number in the last ${stats.days} days"
                        else
                            "Last call: ${formatLastCall(stats.lastCallMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${stats.missed} missed • ${stats.rejected} rejected • ${stats.answered} answered",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.86f)
                    )
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.IncomingCallContent() {
        Spacer(Modifier.weight(1f))

        Button(
            onClick = { CallSession.answer() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 5.dp)
        ) {
            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(22.dp))
            Text("  ANSWER", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Block,
                label = "Block",
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                onClick = {
                    val blocked = CallSession.blockNumber(this@CallActivity)
                    if (!blocked) {
                        Toast.makeText(
                            this@CallActivity,
                            "CallerLens could not add this number to Android's blocked list.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PhoneForwarded,
                label = "Voicemail",
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                onClick = { CallSession.sendToVoicemail() }
            )
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CallEnd,
                label = "Reject",
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = { CallSession.reject() }
            )
        }
    }

    @Composable
    private fun ColumnScope.ActiveCallContent(state: CallUiState, stats: NumberStats) {
        CallHistoryCard(stats, compact = true)
        Spacer(Modifier.weight(1f))

        Text(
            text = "CALL CONTROLS",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.64f),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.MicOff,
                label = if (state.isMuted) "Unmute" else "Mute",
                containerColor = if (state.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                contentColor = if (state.isMuted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                onClick = { CallSession.toggleMute() }
            )
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.VolumeUp,
                label = if (state.isSpeaker) "Earpiece" else "Speaker",
                containerColor = if (state.isSpeaker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                contentColor = if (state.isSpeaker) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                onClick = { CallSession.toggleSpeaker() }
            )
            CallActionButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CallEnd,
                label = "Hang up",
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = { CallSession.hangUp() }
            )
        }
    }

    @Composable
    private fun CallActionButton(
        modifier: Modifier,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        containerColor: Color,
        contentColor: Color,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.height(76.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.DialingCallContent() {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connecting…",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "CallerLens will show call controls when the connection is ready.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { CallSession.hangUp() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = null)
            Text("  HANG UP", style = MaterialTheme.typography.labelLarge)
        }
    }

    @Composable
    private fun CallHistoryCard(stats: NumberStats, compact: Boolean = false) {
        val totalText = when (stats.total) {
            0 -> "No previous calls in the last ${stats.days} days"
            1 -> "This number called once in the last ${stats.days} days"
            else -> "This number called ${stats.total} times in the last ${stats.days} days"
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = if (compact) 14.dp else 17.dp)
            ) {
                Text(
                    text = "CALL HISTORY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.background,
                    letterSpacing = 1.1.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = totalText,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Last call: ${formatLastCall(stats.lastCallMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )

                if (!compact) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatTile(Modifier.weight(1f), stats.missed, "Missed")
                        StatTile(Modifier.weight(1f), stats.rejected, "Rejected")
                        StatTile(Modifier.weight(1f), stats.answered, "Answered")
                    }
                }
            }
        }
    }

    @Composable
    private fun StatTile(modifier: Modifier, value: Int, label: String) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value.toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
