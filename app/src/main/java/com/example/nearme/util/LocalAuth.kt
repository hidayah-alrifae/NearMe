package com.example.nearme.util



import android.content.Context
import java.util.UUID

/**
 * LocalAuth handles user identity generation and storage.
 * On first launch, it creates a unique UUID that persists across app sessions.
 * This UUID is used to identify the user to nearby devices.
 */
object LocalAuth {

    private const val PREFS_NAME = "nearme_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    /**
     * Returns the user's unique ID.
     * If no ID exists (first launch), generates a new UUID and saves it.
     */
    fun getUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var userId = prefs.getString(KEY_USER_ID, null)
        if (userId == null) {
            userId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, userId).apply()
        }
        return userId
    }

    /**
     * Returns the last 4 characters of the UUID.
     * Used to distinguish users with the same display name (e.g., "Ahmed#a4f2").
     */
    fun getShortId(context: Context): String {
        return getUserId(context).takeLast(4)
    }

    /**
     * Returns the user's display name.
     * Defaults to "User#xxxx" if no name has been set.
     */
    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISPLAY_NAME, "User") ?: "User"    }

    /**
     * Saves a new display name chosen by the user.
     */
    fun setDisplayName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }
}