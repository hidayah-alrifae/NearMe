package com.example.nearme.data

import androidx.room.*
import com.example.nearme.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // saves a new message to the database
    @Insert
    suspend fun insertMessage(message: Message)

    // gets all messages for a specific chat, sorted oldest first
    // Flow means the UI updates automatically when new messages arrive
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<Message>>

    // deletes all messages in a specific conversation
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    // deletes a single message by its id (delete for me feature)
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    // gets every message from all conversations
    @Query("SELECT * FROM messages")
    fun getAllMessages(): Flow<List<Message>>

    // wipes the entire messages table (clear all chats)
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}