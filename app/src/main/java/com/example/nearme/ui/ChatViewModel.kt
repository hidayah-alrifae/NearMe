package com.example.nearme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import com.example.nearme.repository.NearMeRepository
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ChatViewModel manages the active chat session.
 * It does NOT own NearbyManager — the repository does.
 * It talks to the repository for NC operations and to
 * MessageDao for database operations.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // Database access for saving and loading messages
    private val messageDao = AppDatabase.getInstance(application).messageDao()

    // This device's display name (shown as sender on outgoing messages)
    private val displayName = LocalAuth.getDisplayName(application)

    // This device's shortId
    private val shortId = LocalAuth.getShortId(application)

    // The singleton repository — same instance the service uses
    private val repository = NearMeRepository.getInstance(application)

    // Live message list — UI collects this and redraws automatically
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    // NC endpoint ID — set when the connection is established via SharedFlow
    private var currentEndpointId: String? = null

    // The other person's shortId — used as the Room database key
    private var currentConversationId: String? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /**
     * Opens a chat session with the given user.
     * - Tells repository this conversation is active (suppresses notifications)
     * - Asks repository to start NC discovery targeting this shortId
     * - Starts collecting messages from Room
     * - Starts collecting the connected endpointId from repository
     */
    fun startChat(conversationId: String) {
        currentConversationId = conversationId

        repository.setActiveConversation(conversationId)

        // Check if already connected to this person (from a previous session)
        val existingEndpoint = repository.getEndpointForShortId(conversationId)
        if (existingEndpoint != null) {
            // Already connected — use the existing endpoint directly
            currentEndpointId = existingEndpoint
            _isConnected.value = true
        } else {
            // Not connected yet — start NC discovery
            repository.connectToUser(conversationId)
        }

        // Collect messages from Room database
        viewModelScope.launch {
            messageDao.getMessages(conversationId).collect { messageList ->
                _messages.value = messageList
            }
        }

        // Collect new connections — but ONLY accept ones for OUR conversation
        viewModelScope.launch {
            repository.connectedEndpointId.collect { (peerShortId, endpointId) ->
                // CRITICAL: only accept connections for the person we're chatting with
                if (peerShortId == currentConversationId) {
                    currentEndpointId = endpointId
                    _isConnected.value = true
                }
            }
        }
    }

    /**
     * Sends a message to the connected peer.
     * Silently returns if NC hasn't connected yet (endpointId is null).
     * Saves to Room first (so it appears in UI), then sends via NC.
     */
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
            // Save locally first — UI updates immediately via Room Flow
            messageDao.insertMessage(message)
            // Then send over NC to the other device
            repository.sendNcMessage(endpointId, text)
        }
    }

    /**
     * Called when the user leaves the chat screen.
     * Clears active conversation (so future messages trigger notifications)
     * and disconnects from the NC endpoint.
     */
    override fun onCleared() {
        super.onCleared()
        // Clear active conversation — future messages will post notifications
        repository.setActiveConversation(null)
        // Do NOT disconnect — the connection stays alive in the service layer
        // so messages can still arrive and trigger notifications
    }

    fun clearActiveConversation() {
        repository.setActiveConversation(null)
    }

    fun restoreActiveConversation() {
        val convId = currentConversationId
        if (convId != null) {
            repository.setActiveConversation(convId)
        }
    }

}