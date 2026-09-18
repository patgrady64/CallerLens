package com.pgdevhouse.callerlens.data

import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Returns a stable key for a real dialable phone number.
 *
 * Whenever possible this is E.164, so values such as 4105551234,
 * (410) 555-1234, and +1 410-555-1234 resolve to the same caller.
 * Special/private/unknown values return null instead of being grouped
 * as though they were a real phone number.
 */
fun normalizedPhoneKey(number: String): String? {
    val raw = number.trim()
    if (raw.isEmpty()) return null

    val normalized = PhoneNumberUtils.normalizeNumber(raw)
    val digits = normalized.filter(Char::isDigit)
    if (digits.length < 3) return null

    val countryIso = Locale.getDefault().country
    if (countryIso.isNotBlank()) {
        val e164 = runCatching {
            PhoneNumberUtils.formatNumberToE164(raw, countryIso)
        }.getOrNull()
        if (!e164.isNullOrBlank()) return e164
    }

    // Useful fallback for NANP numbers if E.164 parsing was unavailable.
    if (countryIso.equals("US", ignoreCase = true) || countryIso.equals("CA", ignoreCase = true)) {
        if (digits.length == 10) return "+1$digits"
        if (digits.length == 11 && digits.startsWith("1")) return "+$digits"
    }

    return normalized.takeIf { it.isNotBlank() }
}

fun isUsablePhoneNumber(
    number: String,
    presentation: Int = TelecomManager.PRESENTATION_ALLOWED
): Boolean = presentation == TelecomManager.PRESENTATION_ALLOWED && normalizedPhoneKey(number) != null

fun fallbackCallerName(presentation: Int): String = when (presentation) {
    TelecomManager.PRESENTATION_RESTRICTED -> "Private Caller"
    TelecomManager.PRESENTATION_PAYPHONE -> "Payphone"
    TelecomManager.PRESENTATION_UNKNOWN -> "Unknown Caller"
    else -> "Unknown Caller"
}

fun formatPresentedPhoneNumber(number: String, presentation: Int): String = when (presentation) {
    TelecomManager.PRESENTATION_RESTRICTED -> "Private number"
    TelecomManager.PRESENTATION_PAYPHONE -> "Payphone"
    TelecomManager.PRESENTATION_UNKNOWN -> "Unknown number"
    else -> formatPhoneNumber(number)
}

fun formatPhoneNumber(number: String): String {
    if (number.isBlank()) return "Unknown number"
    return PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
}

fun formatLastCall(timestamp: Long?): String {
    if (timestamp == null) return "No previous calls"
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    return when {
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> "Today at $time"

        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR) == 1 -> "Yesterday at $time"

        else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
}
