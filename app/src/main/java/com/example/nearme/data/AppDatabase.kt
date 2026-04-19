package com.example.nearme.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nearme.model.Message

// tells Room: this database has one table (Message), and it's version 2
@Database(entities = [Message::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

    // gives access to all the message operations we defined in MessageDao
    abstract fun messageDao(): MessageDao

    // Singleton: makes sure only ONE database connection exists in the whole app
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // if database already exists, return it
            // if not, create it for the first time
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nearme_database" // the actual file name saved on the phone
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}