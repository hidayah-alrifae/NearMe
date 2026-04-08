package com.example.nearme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.nearme.ui.NearMeApp
import com.example.nearme.ui.theme.NearMeTheme
import android.os.Build

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
}