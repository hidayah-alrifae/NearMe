package com.example.nearme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.nearme.ui.NearMeApp
import com.example.nearme.ui.theme.NearMeTheme
import android.os.Build
import android.content.Context

/**
 * Main entry point of the app.
 * Launches NearMeApp which handles all navigation.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enableEdgeToEdge()
        }
        setContent {
            NearMeTheme {
                NearMeApp()
            }
        }
    }
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("nearme_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("language", "ENGLISH") ?: "ENGLISH"
        val locale = if (langStr == "ARABIC") java.util.Locale("ar") else java.util.Locale("en")

        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}