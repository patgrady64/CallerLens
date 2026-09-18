package com.pgdevhouse.callerlens.data

import android.telephony.PhoneNumberUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
