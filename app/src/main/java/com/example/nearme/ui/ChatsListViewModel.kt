package com.example.nearme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import com.example.nearme.repository.NearMeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One row in the Chats list */
data class ChatSummary(
    val conversationId: String,   // = other person's shortId
    val displayName: String,      // best-guess name
    val lastMessage: String,
    val timestamp: Long,
    val isOnline: Boolean,        // is this shortId currently in discoveredUsers?
    val unreadCount: Int = 0      // reserved — always 0 until you add a `read` flag
)

class ChatsListViewModel(application: Application) : AndroidViewModel(application) {

    private val messageDao = AppDatabase.getInstance(application).messageDao()
    private val repository = NearMeRepository.getInstance(application)

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats

    init {
        viewModelScope.launch {
            combine(
                messageDao.getAllMessages(),
                repository.discoveredUsers
            ) { messages, nearby ->
                val nearbyIds = nearby.associateBy { it.shortId }

                messages
                    .groupBy { it.conversationId }
                    .map { (convId, msgs) ->
                        val last = msgs.maxByOrNull { it.timestamp }!!
                        val onlineUser = nearbyIds[convId]

                        val cachedName = msgs
                            .filter { !it.isFromMe && it.senderName.isNotBlank() }
                            .maxByOrNull { it.timestamp }
                            ?.senderName

                        ChatSummary(
                            conversationId = convId,
                            displayName = onlineUser?.displayName ?: cachedName ?: convId,
                            lastMessage = preview(last),
                            timestamp = last.timestamp,
                            isOnline = onlineUser != null,
                            unreadCount = 0
                        )
                    }
                    .sortedByDescending { it.timestamp }
            }.collect { summaries ->
                _chats.value = summaries
            }
        }
    }

    /** Turns a Message into a one-line preview for the list */
    private fun preview(m: Message): String {
        return when {
            m.mimeType?.startsWith("image/") == true -> "📷 Photo"
            m.mimeType?.startsWith("video/") == true -> "🎬 Video"
            m.mimeType != null -> "📄 ${m.content}"
            else -> m.content
        }
    }
}
