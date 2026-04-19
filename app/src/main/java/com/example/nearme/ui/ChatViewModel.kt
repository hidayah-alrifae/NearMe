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

    // the database  to save and load messages
    private val messageDao = AppDatabase.getInstance(application).messageDao()

    // the user's own name and shortId
    private val displayName = LocalAuth.getDisplayName(application)
    private val shortId = LocalAuth.getShortId(application)

    // the messages shown on screen  Flow so UI updates automatically
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    // who we're chatting with  their endpointId from Nearby Connections
    private var currentEndpointId: String? = null

    // who we're chatting with  their shortId (used as conversationId in database)
    private var currentConversationId: String? = null

    // NearbyManager  handles connection and sending/receiving
    // when a message arrives, it saves it to database and updates the screen
    private val nearbyManager = NearbyManager(application, { endpointId, messageText ->
        viewModelScope.launch {
            val message = Message(
                conversationId = currentConversationId ?: endpointId,
                senderName = "Other",
                content = messageText,
                isFromMe = false
            )
            messageDao.insertMessage(message)
        }
    }, { endpointId ->
        // connection established  save the REAL endpointId
        currentEndpointId = endpointId
    })

    // opens a chat  loads messages from database and starts listening for new ones
    fun startChat(conversationId: String, endpointId: String) {
        currentConversationId = conversationId
        currentEndpointId = endpointId

        // start Nearby Connections  advertise and discover to find the other phone
        nearbyManager.startAdvertising(displayName)
        nearbyManager.startDiscovery()

        // load messages from database
        viewModelScope.launch {
            messageDao.getMessages(conversationId).collect { messageList ->
                _messages.value = messageList
            }
        }
    }

    // sends a message  saves to database and sends through Nearby Connections
    fun sendMessage(text: String) {
        val endpointId = currentEndpointId ?: return
        val conversationId = currentConversationId ?: return

        val message = Message(
            conversationId = conversationId,
            senderName = displayName,
            content = text,
            isFromMe = true
        )

        viewModelScope.launch {
            messageDao.insertMessage(message)
            nearbyManager.sendMessage(endpointId, text)
        }
    }

    // clean up when leaving the chat
    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopAllConnections()
    }

}