package com.example.nearme.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import com.example.nearme.repository.NearMeRepository
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * ChatViewModel manages the active chat session.
 * It does NOT own NearbyManager — the repository does.
 * It talks to the repository for NC operations and to
 * MessageDao for database operations.
 */
@RequiresApi(Build.VERSION_CODES.Q)
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
    private val _pendingIncomingRequest = MutableStateFlow<NearMeRepository.IncomingFileRequest?>(null)
    val pendingIncomingRequest: StateFlow<NearMeRepository.IncomingFileRequest?> = _pendingIncomingRequest
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
        // Seed any pending file request for this peer (e.g. if user wasn't in chat when it arrived)
        _pendingIncomingRequest.value = repository.getPendingFileRequest(conversationId)

// Listen for new file requests while chat is open
        viewModelScope.launch {
            repository.incomingFileRequests.collect { req ->
                if (req.senderShortId == currentConversationId) {
                    _pendingIncomingRequest.value = req
                }
            }
        }

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
     * Sends a text message to the connected peer.
     * Message is saved to Room immediately (so UI updates),
     * then sent via NC with a message ID for delivery tracking.
     * Status starts as "sent" — changes to "delivered" when ACK arrives.
     */
    fun sendMessage(text: String) {
        val endpointId = currentEndpointId ?: return
        val conversationId = currentConversationId ?: return

        // Use timestamp as message ID — unique enough for P2P
        val messageId = System.currentTimeMillis().toString()

        val message = Message(
            id = messageId,
            conversationId = conversationId,
            senderName = displayName,
            content = text,
            isFromMe = true,
            status = "sent"  // ✓ waiting for ACK
        )

        viewModelScope.launch {
            // Save locally first — UI updates immediately via Room Flow
            messageDao.insertMessage(message)
            repository.sendChatMessage(endpointId, messageId, text)
        }
    }

    /**
     * Sends a file (image/video/document) to the connected peer.
     * Called by ChatScreen when the user picks a file from the gallery.
     *
     * Steps:
     * 1. Read the file name and MIME type from the content URI
     * 2. Copy the file to app's cache (because content:// URIs can expire)
     * 3. Save sender's permanent copy to filesDir/sent_files/
     * 4. Save Message to Room with status="sending" (UI shows ⏳)
     * 5. Send through repository → NearbyManager (with messageId for ACK)
     * 6. Update status to "sent" (UI shows ✓)
     * 7. When ACK arrives (handled by repository), status → "delivered" (✓✓)
     */
    fun sendFile(uri: Uri) {
        val endpointId = currentEndpointId ?: return
        val conversationId = currentConversationId ?: return
        val context = getApplication<Application>()

        viewModelScope.launch {
            try {
                val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                // Copy URI → temp file (NC needs a real File, not a content URI)
                val tempFile = File(context.cacheDir, "send_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@launch

                // 25 MB cap
                if (tempFile.length() > 25 * 1024 * 1024L) {
                    Log.w("ChatVM", "File too large: ${tempFile.length()} bytes")
                    tempFile.delete()
                    return@launch
                }

                // Sender's permanent copy (for showing the file in our own chat bubble)
                val sentDir = File(context.filesDir, "sent_files").apply { mkdirs() }
                val permanentFile = File(sentDir, fileName)
                tempFile.copyTo(permanentFile, overwrite = true)

                val messageId = System.currentTimeMillis().toString()

                // Insert with awaiting_approval — flips to "sending" on ACCEPT, "rejected" on REJECT
                messageDao.insertMessage(
                    Message(
                        id = messageId,
                        conversationId = conversationId,
                        senderName = displayName,
                        content = fileName,
                        isFromMe = true,
                        status = "awaiting_approval",
                        filePath = permanentFile.absolutePath,
                        mimeType = mimeType
                    )
                )

                // Ask permission instead of sending immediately. Repository handles tempFile lifetime.
                repository.requestFileTransfer(endpointId, messageId, tempFile, fileName, mimeType)
            } catch (e: Exception) {
                Log.e("ChatVM", "Failed to request file transfer: ${e.message}")
            }
        }
    }

    fun acceptFileRequest() {
        val req = _pendingIncomingRequest.value ?: return
        repository.respondToFileRequest(req.messageId, accepted = true)
        _pendingIncomingRequest.value = null
    }

    fun rejectFileRequest() {
        val req = _pendingIncomingRequest.value ?: return
        repository.respondToFileRequest(req.messageId, accepted = false)
        _pendingIncomingRequest.value = null
    }
    /**
     * Extracts the display name of a file from a content:// URI.
     * Uses the ContentResolver to query the OpenableColumns.DISPLAY_NAME column.
     * Returns null if the name can't be determined.
     */
    private fun getFileName(context: Application, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    name = cursor.getString(index)
                }
            }
        }
        return name
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
    /** Deletes a single message from this conversation. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageDao.deleteMessage(messageId)
        }
    }
}