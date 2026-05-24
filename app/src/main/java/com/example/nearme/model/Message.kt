package com.example.nearme.model
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message (
    //a unique number for each message (so the database can identify it)
    @PrimaryKey
    val id : String = System.currentTimeMillis().toString(),

    //which chat this message belongs to (Ahmed's chat vs Sara's chat)
    val conversationId: String,

    //who sent it
    val senderName: String,

    //the actual text
    val content: String,

    //when it was sent
    val timestamp : Long = System.currentTimeMillis() ,

    //did I send this, or did the other person send it?
    val isFromMe: Boolean = true,

    //was this message actually delivered to the other phone?
    val isSent: Boolean = true,

    // Path to the file on this device's local storage (null for text only messages)
    val filePath: String? = null,

    // MIME type of the attached file (e.g. "image/jpeg", "video/mp4")
    // Used by the UI to decide how to display the attachment
    val mimeType: String? = null
)