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
    fun sendFile(uri: android.net.Uri) {
        val id = groupId ?: return
        val context = getApplication<Application>()

        viewModelScope.launch {
            try {
                val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                val tempFile = java.io.File(context.cacheDir, "send_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@launch

                if (tempFile.length() > 25 * 1024 * 1024L) {
                    tempFile.delete(); return@launch
                }

                val sentDir = java.io.File(context.filesDir, "sent_files").apply { mkdirs() }
                val permanentFile = java.io.File(sentDir, fileName)
                tempFile.copyTo(permanentFile, overwrite = true)

                val messageId = System.currentTimeMillis().toString()
                messageDao.insertMessage(
                    Message(
                        id = messageId,
                        conversationId = id,
                        senderName = myName,
                        content = fileName,
                        isFromMe = true,
                        status = "sending",
                        filePath = permanentFile.absolutePath,
                        mimeType = mimeType
                    )
                )

                repository.sendGroupFile(id, tempFile, fileName, mimeType, messageId)
                messageDao.updateStatus(messageId, "sent")

                kotlinx.coroutines.delay(5000)
                tempFile.delete()
            } catch (e: Exception) {
                android.util.Log.e("GroupVM", "Failed to send group file: ${e.message}")
            }
        }
    }

    private fun getFileName(context: android.content.Context, uri: android.net.Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        return name
    }
}
