package com.example.nearme.ui

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import com.example.nearme.repository.NearMeRepository
import com.example.nearme.util.GroupInfo
import com.example.nearme.util.GroupStore
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.Q)
class GroupChatViewModel(application: Application) : AndroidViewModel(application) {

    private val messageDao = AppDatabase.getInstance(application).messageDao()
    private val repository = NearMeRepository.getInstance(application)
    private val myName = LocalAuth.getDisplayName(application)

    private var groupId: String? = null

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _group = MutableStateFlow<GroupInfo?>(null)
    val group: StateFlow<GroupInfo?> = _group

    fun openGroup(id: String) {
        groupId = id
        repository.setActiveConversation(id)
        viewModelScope.launch {
            messageDao.getMessages(id).collect { _messages.value = it }
        }
        viewModelScope.launch {
            GroupStore.groups.collect { list ->
                _group.value = list.firstOrNull { it.groupId == id }
            }
        }
    }

    fun sendMessage(text: String) {
        val id = groupId ?: return
        if (text.isBlank()) return
        val messageId = System.currentTimeMillis().toString()
        viewModelScope.launch {
            messageDao.insertMessage(
                Message(
                    id = messageId,
                    conversationId = id,
                    senderName = myName,
                    content = text,
                    isFromMe = true,
                    status = "sent"
                )
            )
            repository.sendGroupMessage(id, messageId, text)
        }
    }
    val nearbyUsers: kotlinx.coroutines.flow.StateFlow<List<com.example.nearme.model.UserProfile>> =
        repository.discoveredUsers

    fun addMembers(shortIds: List<String>) {
        val id = groupId ?: return
        if (shortIds.isEmpty()) return
        repository.inviteMembersToGroup(id, shortIds)
    }

    fun leaveGroup() {
        groupId?.let { repository.leaveGroup(it) }
    }

    fun clearActive() {
        repository.setActiveConversation(null)
    }
}
