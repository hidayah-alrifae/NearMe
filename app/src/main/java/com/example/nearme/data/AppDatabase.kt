package com.example.nearme.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nearme.model.Message

// Version 5: replaced isSent with status field on Message
@Database(entities = [Message::class], version = 5)
abstract class AppDatabase : RoomDatabase() {

    // Gives access to all the message operations we defined in MessageDao
    abstract fun messageDao(): MessageDao

    // Singleton: makes sure only ONE database connection exists in the whole app
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // If database already exists, return it
            // If not, create it for the first time
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nearme_database"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}