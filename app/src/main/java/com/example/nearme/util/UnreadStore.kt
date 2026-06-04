package com.example.nearme.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.nearme.data.AppDatabase
import kotlinx.coroutines.flow.combine

object UnreadStore {

    private const val PREFS = "nearme_prefs"
    private fun keyFor(conversationId: String) = "last_read_$conversationId"

    /** Stamp this conversation as read up to now. */
    fun markRead(context: Context, conversationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(keyFor(conversationId), System.currentTimeMillis())
            .apply()
    }

    fun lastRead(context: Context, conversationId: String): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(keyFor(conversationId), 0L)
    }
}

/** Composable helper — total unread count across all conversations. */
@Composable
fun rememberTotalUnreadCount(): State<Int> {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).messageDao() }
    return produceState(initialValue = 0, dao) {
        dao.getAllMessages().collect { messages ->
            val prefs = context.getSharedPreferences("nearme_prefs", Context.MODE_PRIVATE)
            value = messages
                .filter { !it.isFromMe }
                .groupBy { it.conversationId }
                .map { (convId, msgs) ->
                    val lastRead = prefs.getLong("last_read_$convId", 0L)
                    msgs.count { it.timestamp > lastRead }
                }
                .sum()
        }
    }
}

/** Composable helper — unread count for one conversation (used by ChatsList rows). */
@Composable
fun rememberUnreadFor(conversationId: String): State<Int> {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).messageDao() }
    return produceState(initialValue = 0, conversationId) {
        dao.getAllMessages().collect { messages ->
            val lastRead = UnreadStore.lastRead(context, conversationId)
            value = messages.count {
                it.conversationId == conversationId && !it.isFromMe && it.timestamp > lastRead
            }
        }
    }
}
