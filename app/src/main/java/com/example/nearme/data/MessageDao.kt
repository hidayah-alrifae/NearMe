package com.example.nearme.data

import androidx.room.*
import com.example.nearme.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // Saves a new message to the database
    @Insert
    suspend fun insertMessage(message: Message)

    // Gets all messages for a specific chat, sorted oldest first
    // Flow means the UI updates automatically when new messages arrive
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<Message>>

    // Updates a message's delivery status ("sending" → "sent" → "delivered")
    // Called when:
    //   - File payload dispatched → "sent"
    //   - ACK received from receiver → "delivered"
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    // Gets messages that haven't been delivered yet (for future retry logic)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isFromMe = 1 AND status != 'delivered' ORDER BY timestamp ASC")
    suspend fun getUndeliveredMessages(conversationId: String): List<Message>

    // Deletes all messages in a specific conversation
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    // Deletes a single message by its id (delete for me feature)
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    // Gets every message from all conversations
    @Query("SELECT * FROM messages")
    fun getAllMessages(): Flow<List<Message>>

    // Wipes the entire messages table (clear all chats)
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}