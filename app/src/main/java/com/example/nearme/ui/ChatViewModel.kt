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
                // Step 1: Get the file name from the content URI
                val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"

                // Step 2: Get MIME type
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                // Step 3: Copy to a temp file in cache
                // We can't pass a content:// URI to NC — it needs a real File
                val tempFile = File(context.cacheDir, "send_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@launch

                // Step 4: Check file size — limit to 25MB
                val maxSize = 25 * 1024 * 1024L  // 25 MB
                if (tempFile.length() > maxSize) {
                    Log.w("ChatVM", "File too large: ${tempFile.length()} bytes")
                    tempFile.delete()
                    return@launch
                }

                // Step 5: Save the sender's copy to permanent storage
                val sentDir = File(context.filesDir, "sent_files")
                sentDir.mkdirs()
                val permanentFile = File(sentDir, fileName)
                tempFile.copyTo(permanentFile, overwrite = true)

                // Step 6: Generate message ID for this file message
                val messageId = System.currentTimeMillis().toString()

                // Step 7: Save to Room so it appears in our chat immediately
                val message = Message(
                    id = messageId,
                    conversationId = conversationId,
                    senderName = displayName,
                    content = fileName,
                    isFromMe = true,
                    status = "sending",  // ⏳ file transfer in progress
                    filePath = permanentFile.absolutePath,
                    mimeType = mimeType
                )
                messageDao.insertMessage(message)

                // Step 8: Send the file via NC (with messageId for ACK tracking)
                repository.sendChatFile(endpointId, tempFile, fileName, mimeType, messageId)
                // Step 9: File payload dispatched — update to "sent" (✓)
                messageDao.updateStatus(messageId, "sent")

                // Clean up temp file after a delay (give NC time to read it)
                // NC reads the file asynchronously, so we can't delete immediately
                kotlinx.coroutines.delay(5000)
                tempFile.delete()

            } catch (e: Exception) {
                Log.e("ChatVM", "Failed to send file: ${e.message}")
            }
        }
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
}