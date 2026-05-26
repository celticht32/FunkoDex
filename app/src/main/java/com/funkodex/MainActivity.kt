package com.funkodex

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.FunkoDexNavHost
import com.funkodex.ui.screens.SplashScreen
import com.funkodex.ui.screens.settings.SettingsViewModel
import com.funkodex.ui.theme.FunkoDexTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Theme is driven by persisted user preference
            val settingsVm: SettingsViewModel = hiltViewModel()
            val currentTheme by settingsVm.currentTheme.collectAsState()

            // Deep-link target from price-alert notifications (NAVIGATE_TO_ITEM extra)
            val deepLinkItemId = intent?.getStringExtra("NAVIGATE_TO_ITEM")

            FunkoDexTheme(appTheme = currentTheme) {
                var splashDone by remember { mutableStateOf(false) }

                if (!splashDone) {
                    SplashScreen(onSplashComplete = { splashDone = true })
                } else {
                    FunkoDexNavHost(deepLinkItemId = deepLinkItemId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification taps when the app is already running
        setIntent(intent)
    }
}
