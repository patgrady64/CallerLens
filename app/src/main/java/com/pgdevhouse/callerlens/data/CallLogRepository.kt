package com.pgdevhouse.callerlens.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DAY_MS = 24L * 60L * 60L * 1000L

data class NumberStats(
    val total: Int = 0,
    val missed: Int = 0,
    val rejected: Int = 0,
    val answered: Int = 0,
    val blocked: Int = 0,
    val lastCallMillis: Long? = null,
    val days: Int = 7
)

data class RecentCaller(
    val number: String,
    val cachedName: String?,
    val count: Int,
    val lastCallMillis: Long,
    val lastType: Int
)

class CallLogRepository(private val context: Context) {

    suspend fun statsFor(number: String, days: Int = 7): NumberStats = withContext(Dispatchers.IO) {
        if (!hasCallLogPermission() || number.isBlank()) return@withContext NumberStats(days = days)

        val cutoff = System.currentTimeMillis() - days * DAY_MS
        var total = 0
        var missed = 0
        var rejected = 0
        var answered = 0
        var blocked = 0
        var lastCall: Long? = null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(cutoff.toString())

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            args,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

            while (cursor.moveToNext()) {
                val rowNumber = cursor.getString(numberIndex) ?: continue
                if (!sameNumber(rowNumber, number)) continue

                when (cursor.getInt(typeIndex)) {
                    CallLog.Calls.INCOMING_TYPE -> {
                        total++
                        answered++
                    }
                    CallLog.Calls.MISSED_TYPE -> {
                        total++
                        missed++
                    }
                    CallLog.Calls.REJECTED_TYPE -> {
                        total++
                        rejected++
                    }
                    CallLog.Calls.BLOCKED_TYPE -> {
                        blocked++
                    }
                    else -> continue
                }

                val date = cursor.getLong(dateIndex)
                if (lastCall == null || date > lastCall!!) lastCall = date
            }
        }

        NumberStats(total, missed, rejected, answered, blocked, lastCall, days)
    }

    suspend fun recentCallers(days: Int = 30, limit: Int = 100): List<RecentCaller> = withContext(Dispatchers.IO) {
        if (!hasCallLogPermission()) return@withContext emptyList()

        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val grouped = linkedMapOf<String, MutableRecentCaller>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(cutoff.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeIndex)
                if (type !in setOf(
                        CallLog.Calls.INCOMING_TYPE,
                        CallLog.Calls.MISSED_TYPE,
                        CallLog.Calls.REJECTED_TYPE,
                        CallLog.Calls.BLOCKED_TYPE
                    )
                ) continue

                val number = cursor.getString(numberIndex) ?: continue
                val key = normalizedKey(number)
                val date = cursor.getLong(dateIndex)
                val existing = grouped[key]
                if (existing == null) {
                    grouped[key] = MutableRecentCaller(
                        number = number,
                        cachedName = cursor.getString(nameIndex),
                        count = 1,
                        lastCallMillis = date,
                        lastType = type
                    )
                } else {
                    existing.count++
                }
            }
        }

        grouped.values
            .sortedByDescending { it.lastCallMillis }
            .take(limit)
            .map { RecentCaller(it.number, it.cachedName, it.count, it.lastCallMillis, it.lastType) }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun sameNumber(a: String, b: String): Boolean = PhoneNumberUtils.compare(a, b)

    private fun normalizedKey(number: String): String {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        return if (normalized.length > 10) normalized.takeLast(10) else normalized
    }

    private data class MutableRecentCaller(
        val number: String,
        val cachedName: String?,
        var count: Int,
        val lastCallMillis: Long,
        val lastType: Int
    )
}
