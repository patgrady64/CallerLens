package com.pgdevhouse.callerlens.data

import android.content.Context
import android.provider.BaseColumns
import android.provider.BlockedNumberContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BlockedNumberItem(
    val id: Long,
    val number: String
)

class BlockedNumberRepository(private val context: Context) {

    suspend fun blockedNumbers(): List<BlockedNumberItem> = withContext(Dispatchers.IO) {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return@withContext emptyList()

        runCatching {
            val items = mutableListOf<BlockedNumberItem>()
            val projection = arrayOf(
                BaseColumns._ID,
                BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER
            )

            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                projection,
                null,
                null,
                "${BaseColumns._ID} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val originalIndex = cursor.getColumnIndexOrThrow(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
                )
                val e164Index = cursor.getColumnIndexOrThrow(
                    BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER
                )

                while (cursor.moveToNext()) {
                    val original = cursor.getString(originalIndex).orEmpty()
                    val e164 = cursor.getString(e164Index).orEmpty()
                    val number = original.ifBlank { e164 }
                    if (number.isNotBlank()) {
                        items += BlockedNumberItem(cursor.getLong(idIndex), number)
                    }
                }
            }
            items
        }.getOrDefault(emptyList())
    }

    suspend fun unblock(item: BlockedNumberItem): Boolean = withContext(Dispatchers.IO) {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return@withContext false

        runCatching {
            BlockedNumberContract.unblock(context, item.number) > 0
        }.getOrDefault(false)
    }
}
