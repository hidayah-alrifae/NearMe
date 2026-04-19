package com.example.nearme.model
import androidx.room.Entity
import androidx.room.PrimaryKey

import java.util.TimeZone

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
    val isFromMe: Boolean = true
)