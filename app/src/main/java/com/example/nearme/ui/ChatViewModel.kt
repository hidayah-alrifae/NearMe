package com.example.nearme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import com.example.nearme.nearby.NearbyManager
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // the database — to save and load messages
    private val messageDao = AppDatabase.getInstance(application).messageDao()

    // the user's own name and shortId
    private val displayName = LocalAuth.getDisplayName(application)
    private val shortId = LocalAuth.getShortId(application)

    // the messages shown on screen — Flow so UI updates automatically
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    // who we're chatting with — their endpointId from Nearby Connections
    // starts null — gets set only when onConnected fires with the REAL endpoint
    private var currentEndpointId: String? = null

    // who we're chatting with — their shortId (used as conversationId in database)
    private var currentConversationId: String? = null

    // NearbyManager — handles connection and sending/receiving
    private val nearbyManager: NearbyManager = NearbyManager(application,

        // onMessageReceived: a message arrived from the other phone
        { endpointId, messageText ->
            viewModelScope.launch {
                val message = Message(
                    conversationId = currentConversationId ?: endpointId,
                    senderName = "Other",
                    content = messageText,
                    isFromMe = false
                )
                messageDao.insertMessage(message)
            }
        },

        // onConnected: NC connection established — send any unsent messages from database
        { endpointId ->
            viewModelScope.launch {
                currentEndpointId = endpointId
                val conversationId = currentConversationId ?: return@launch
                val unsent = messageDao.getUnsentMessages(conversationId)
                unsent.forEach { msg ->
                    nearbyManager.sendMessage(endpointId, msg.content)
                    messageDao.markAsSent(msg.id)
                }
            }
        },

        // onDisconnected: connection lost — clear endpoint and restart to reconnect
        {
            viewModelScope.launch {
                currentEndpointId = null
                nearbyManager.startAdvertising(displayName)
                nearbyManager.startDiscovery()
            }
        }
    )

    // opens a chat — loads messages from database and starts NC
    fun startChat(conversationId: String) {
        currentConversationId = conversationId
        // currentEndpointId stays null — onConnected will set the REAL one

        // start Nearby Connections — advertise and discover to find the other phone
        nearbyManager.startAdvertising(displayName)
        nearbyManager.startDiscovery()

        // load messages from database
        viewModelScope.launch {
            messageDao.getMessages(conversationId).collect { messageList ->
                _messages.value = messageList
            }
        }
    }

    // sends a message — saves to database immediately, sends or marks unsent
    fun sendMessage(text: String) {
        val conversationId = currentConversationId ?: return

        val endpointId = currentEndpointId
        val connected = endpointId != null

        // save to database — isSent depends on whether we're connected right now
        val message = Message(
            conversationId = conversationId,
            senderName = displayName,
            content = text,
            isFromMe = true,
            isSent = connected
        )

        viewModelScope.launch {
            messageDao.insertMessage(message)
        }

        // if connected, send now — if not, it stays in database as unsent
        // and will be picked up by onConnected when the connection is restored
        if (connected) {
            nearbyManager.sendMessage(endpointId!!, text)
        }
    }

    // clean up when leaving the chat
    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopAllConnections()
    }
}