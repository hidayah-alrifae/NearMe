package com.example.nearme.util

import android.content.Context

/**
 * Tiny persistent store mapping shortId → displayName.
 * Used so we can show a person's name in chats list and notifications
 * even after they've walked out of range and the BLE broadcast is gone.
 */
object ContactStore {

    private const val PREFS = "nearme_contacts"

    /** Save (or update) the display name for a given shortId. */
    fun saveName(context: Context, shortId: String, displayName: String) {
        if (shortId.isBlank() || displayName.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(shortId, displayName)
            .apply()
    }

    /** Returns the saved name for this shortId, or null if we've never seen them. */
    fun getName(context: Context, shortId: String): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(shortId, null)
    }
}
