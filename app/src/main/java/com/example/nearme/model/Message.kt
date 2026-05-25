package com.example.nearme.model
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    // A unique number for each message (so the database can identify it)
    @PrimaryKey
    val id: String = System.currentTimeMillis().toString(),

    // Which chat this message belongs to (Ahmed's chat vs Sara's chat)
    val conversationId: String,

    // Who sent it
    val senderName: String,

    // The actual text (or filename for file messages)
    val content: String,

    // When it was sent
    val timestamp: Long = System.currentTimeMillis(),

    // Did I send this, or did the other person send it?
    val isFromMe: Boolean = true,

    // Delivery status: "sending" → "sent" → "delivered"
    // "sending" = file payload still being transmitted
    // "sent"    = payload dispatched to NC, waiting for ACK
    // "delivered" = receiver confirmed receipt via ACK
    val status: String = "sent",

    // File message fields (null for regular text messages)
    val filePath: String? = null,
    val mimeType: String? = null
)