package com.pgdevhouse.callerlens

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pgdevhouse.callerlens.data.CallLogRepository
import com.pgdevhouse.callerlens.data.NumberStats
import com.pgdevhouse.callerlens.data.RecentCaller
import com.pgdevhouse.callerlens.data.formatLastCall
import com.pgdevhouse.callerlens.data.formatPhoneNumber
import com.pgdevhouse.callerlens.ui.theme.CallerLensTheme
import kotlinx.coroutines.launch

private const val RECENT_PREFS = "caller_lens_recent_callers"
private const val HIDDEN_PREFIX = "hidden_through_"
private const val SETTINGS_PREFS = "caller_lens_settings"
private const val KEEP_SCREEN_ON_KEY = "keep_screen_on_during_call"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialNumber = intent?.data?.schemeSpecificPart.orEmpty()

        setContent {
            CallerLensTheme {
                CallerLensHome(initialNumber = initialNumber)
            }
        }
    }
}

@Composable
private fun MainActivity.CallerLensHome(initialNumber: String) {
    val context = this
    val repository = remember(context) { CallLogRepository(context) }
    val scope = rememberCoroutineScope()

    var defaultDialer by remember { mutableStateOf(holdsDialerRole(context)) }
    var permissionsGranted by remember { mutableStateOf(requiredPermissions().all { hasPermission(context, it) }) }
    var recentCallers by remember { mutableStateOf<List<RecentCaller>>(emptyList()) }
    var dialNumber by remember { mutableStateOf(initialNumber) }
    var fullScreenAllowed by remember { mutableStateOf(canUseFullScreenCalls(context)) }
    var detailCaller by remember { mutableStateOf<RecentCaller?>(null) }
    var detailStats by remember { mutableStateOf<NumberStats?>(null) }
    var keepScreenOnDuringCall by remember {
        mutableStateOf(
            context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEEP_SCREEN_ON_KEY, false)
        )
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        defaultDialer = holdsDialerRole(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = requiredPermissions().all { hasPermission(context, it) }
    }

    val fullScreenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        fullScreenAllowed = canUseFullScreenCalls(context)
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            recentCallers = repository.recentCallers().filterNot { isCallerHidden(context, it) }
        }
    }

    detailCaller?.let { caller ->
        CallerDetailsDialog(
            caller = caller,
            stats = detailStats,
            onDismiss = {
                detailCaller = null
                detailStats = null
            }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 20.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BrandHeader(defaultDialer = defaultDialer)
            }

            if (!defaultDialer || !permissionsGranted || !fullScreenAllowed) {
                item {
                    SectionHeader(
                        title = "Finish setup",
                        subtitle = "CallerLens works best with all phone permissions enabled"
                    )
                }
            }

            if (!defaultDialer) {
                item {
                    SetupCard(
                        title = "Default phone app",
                        body = "Make CallerLens your Phone app so Android routes incoming and active calls through it.",
                        button = "Set CallerLens as default",
                        onClick = {
                            val roleManager = context.getSystemService(RoleManager::class.java)
                            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                            }
                        }
                    )
                }
            }

            if (!permissionsGranted) {
                item {
                    SetupCard(
                        title = "Call history & contacts",
                        body = "These permissions let CallerLens show caller names, frequency, and previous call outcomes.",
                        button = "Grant permissions",
                        onClick = { permissionLauncher.launch(requiredPermissions()) }
                    )
                }
            }

            if (!fullScreenAllowed) {
                item {
                    SetupCard(
                        title = "Incoming-call display",
                        body = "Allow full-screen incoming calls so CallerLens can appear over the lock screen when Android permits it.",
                        button = "Open full-screen call setting",
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                fullScreenLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Call preferences",
                    subtitle = "Simple controls that stay out of the way"
                )
                Spacer(Modifier.height(8.dp))
                PreferenceCard(
                    checked = keepScreenOnDuringCall,
                    onCheckedChange = { enabled ->
                        keepScreenOnDuringCall = enabled
                        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEEP_SCREEN_ON_KEY, enabled)
                            .apply()
                    }
                )
            }

            item {
                SectionHeader(
                    title = "Dialer",
                    subtitle = "Place a call without leaving CallerLens"
                )
                Spacer(Modifier.height(8.dp))
                DialerCard(
                    number = dialNumber,
                    onNumberChange = { dialNumber = it.filter { ch -> ch.isDigit() || ch in "+*#" } },
                    onDigit = { dialNumber += it },
                    onBackspace = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                    onCall = { placeCall(context, dialNumber) }
                )
            }

            item {
                SectionHeader(
                    title = "Recent incoming callers",
                    subtitle = if (recentCallers.isEmpty()) {
                        "Your repeat callers will appear here"
                    } else {
                        "${recentCallers.size} caller${if (recentCallers.size == 1) "" else "s"} • Swipe to remove • Long-press for options"
                    }
                )
            }

            if (!permissionsGranted) {
                item {
                    EmptyStateCard(
                        title = "Call history permission needed",
                        body = "Grant call-log access to see repeat callers and call frequency."
                    )
                }
            } else if (recentCallers.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "No recent incoming callers",
                        body = "CallerLens will group incoming calls from the last 30 days here."
                    )
                }
            } else {
                items(recentCallers, key = { recentCallerKey(it.number) }) { caller ->
                    RecentCallerCard(
                        caller = caller,
                        onCall = { placeCall(context, caller.number) },
                        onDetails = {
                            detailCaller = caller
                            detailStats = null
                            scope.launch {
                                detailStats = repository.statsFor(caller.number, 30)
                            }
                        },
                        onAddToContacts = { addToContacts(context, caller) },
                        onBlock = { blockNumber(context, caller.number) },
                        onRemove = {
                            hideCallerUntilNextCall(context, caller)
                            recentCallers = recentCallers.filterNot {
                                recentCallerKey(it.number) == recentCallerKey(caller.number)
                            }
                            Toast.makeText(
                                context,
                                "Removed from CallerLens recents. It will return if this number calls again.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(defaultDialer: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(29.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CallerLens",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Know who’s calling — and how often.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
            )
        }

        if (defaultDialer) {
            StatusBadge("DEFAULT")
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f)
        )
    }
}

@Composable
private fun PreferenceCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Keep screen on during calls",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (checked) "Display stays awake while CallerLens is handling a call" else "Android may turn the display off normally",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.72f)
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
private fun DialerCard(
    number: String,
    onNumberChange: (String) -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = number,
                onValueChange = onNumberChange,
                placeholder = { Text("Enter a phone number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                ),
                shape = RoundedCornerShape(18.dp),
                trailingIcon = {
                    IconButton(onClick = onBackspace, enabled = number.isNotEmpty()) {
                        Icon(Icons.Default.Backspace, contentDescription = "Delete digit")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    focusedBorderColor = MaterialTheme.colorScheme.background,
                    unfocusedBorderColor = MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                    focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.background
                )
            )

            DialPad(
                onDigit = onDigit,
                onCall = onCall,
                callEnabled = number.isNotBlank()
            )
        }
    }
}

@Composable
private fun DialPad(onDigit: (String) -> Unit, onCall: () -> Unit, callEnabled: Boolean) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { onDigit(digit) },
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.background.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            digit,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Button(
            onClick = onCall,
            enabled = callEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                disabledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.28f),
                disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Default.Call, contentDescription = null)
            Text("  CALL", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
            )
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                )
            ) {
                Text(button)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.72f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCallerCard(
    caller: RecentCaller,
    onCall: () -> Unit,
    onDetails: () -> Unit,
    onAddToContacts: () -> Unit,
    onBlock: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember(caller.number) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRemove()
                true
            } else {
                true
            }
        }
    )

    if (menuOpen) {
        CallerActionsDialog(
            caller = caller,
            onDismiss = { menuOpen = false },
            onDetails = {
                menuOpen = false
                onDetails()
            },
            onCall = {
                menuOpen = false
                onCall()
            },
            onAddToContacts = {
                menuOpen = false
                onAddToContacts()
            },
            onBlock = {
                menuOpen = false
                onBlock()
            },
            onRemove = {
                menuOpen = false
                onRemove()
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Remove from recents",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true }
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                CallerInitialAvatar(caller)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = caller.cachedName?.takeIf { it.isNotBlank() }
                            ?: formatPhoneNumber(caller.number),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!caller.cachedName.isNullOrBlank()) {
                        Text(
                            formatPhoneNumber(caller.number),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        SmallPill("${caller.count} calls")
                        SmallPill(recentTypeLabel(caller.lastType))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Last call ${formatLastCall(caller.lastCallMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    IconButton(onClick = onCall) {
                        Icon(Icons.Default.Call, contentDescription = "Call back")
                    }
                }
            }
        }
    }
}

@Composable
private fun CallerInitialAvatar(caller: RecentCaller) {
    val label = caller.cachedName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.first()
        ?.uppercaseChar()
        ?.toString()
        ?: "?"

    Surface(
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun SmallPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun CallerActionsDialog(
    caller: RecentCaller,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onCall: () -> Unit,
    onAddToContacts: () -> Unit,
    onBlock: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.background,
        textContentColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(26.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    caller.cachedName?.takeIf { it.isNotBlank() }
                        ?: formatPhoneNumber(caller.number),
                    style = MaterialTheme.typography.titleLarge
                )
                if (!caller.cachedName.isNullOrBlank()) {
                    Text(
                        formatPhoneNumber(caller.number),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.75f)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PopupAction(Icons.Default.Info, "Details", onDetails)
                PopupAction(Icons.Default.Call, "Call back", onCall)
                PopupAction(Icons.Default.PersonAdd, "Add to contacts", onAddToContacts)
                PopupAction(Icons.Default.Block, "Block number", onBlock)
                PopupAction(Icons.Default.DeleteOutline, "Remove from recent callers", onRemove)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.background
                )
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun PopupAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.background
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CallerDetailsDialog(
    caller: RecentCaller,
    stats: NumberStats?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.background,
        textContentColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                caller.cachedName?.takeIf { it.isNotBlank() } ?: "Caller details",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    formatPhoneNumber(caller.number),
                    style = MaterialTheme.typography.titleMedium
                )
                if (stats == null) {
                    Text("Loading call details…")
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "${stats.total + stats.blocked} calls in the last ${stats.days} days",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Last call: ${formatLastCall(stats.lastCallMillis ?: caller.lastCallMillis)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                DetailStat(stats.missed, "Missed")
                                DetailStat(stats.rejected, "Rejected")
                                DetailStat(stats.answered, "Answered")
                            }
                            if (stats.blocked > 0) {
                                Text(
                                    "${stats.blocked} blocked",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.background
                )
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun DetailStat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun recentTypeLabel(type: Int): String = when (type) {
    CallLog.Calls.MISSED_TYPE -> "Missed"
    CallLog.Calls.REJECTED_TYPE -> "Rejected"
    CallLog.Calls.BLOCKED_TYPE -> "Blocked"
    CallLog.Calls.INCOMING_TYPE -> "Answered"
    else -> "Incoming"
}

private fun holdsDialerRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java)
    return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
}

private fun canUseFullScreenCalls(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    if (!hasPermission(context, Manifest.permission.CALL_PHONE)) return
    val telecom = context.getSystemService(TelecomManager::class.java)
    telecom.placeCall(Uri.fromParts("tel", number, null), Bundle.EMPTY)
}

private fun addToContacts(context: Context, caller: RecentCaller) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, caller.number)
        caller.cachedName?.takeIf { it.isNotBlank() }?.let {
            putExtra(ContactsContract.Intents.Insert.NAME, it)
        }
    }

    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "No contacts app is available.", Toast.LENGTH_SHORT).show()
    }
}

private fun blockNumber(context: Context, number: String) {
    if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) {
        Toast.makeText(
            context,
            "CallerLens must be the default Phone app before it can block numbers.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    try {
        val values = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
        }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
        Toast.makeText(context, "Number blocked.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Android did not allow CallerLens to block this number.", Toast.LENGTH_LONG).show()
    }
}

private fun hideCallerUntilNextCall(context: Context, caller: RecentCaller) {
    context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(HIDDEN_PREFIX + recentCallerKey(caller.number), caller.lastCallMillis)
        .apply()
}

private fun isCallerHidden(context: Context, caller: RecentCaller): Boolean {
    val hiddenThrough = context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE)
        .getLong(HIDDEN_PREFIX + recentCallerKey(caller.number), Long.MIN_VALUE)
    return hiddenThrough >= caller.lastCallMillis
}

private fun recentCallerKey(number: String): String {
    val normalized = PhoneNumberUtils.normalizeNumber(number)
    return if (normalized.length > 10) normalized.takeLast(10) else normalized
}
