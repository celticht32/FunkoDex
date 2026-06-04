package com.funkodex

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.FunkoDexNavHost
import com.funkodex.ui.screens.settings.SettingsViewModel
import com.funkodex.ui.theme.FunkoDexTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()  // must be called before super/setContent
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val currentTheme by settingsVm.currentTheme.collectAsState()

            val rawDeepLink    = intent?.getStringExtra("NAVIGATE_TO_ITEM")
            val deepLinkItemId = rawDeepLink
                ?.takeIf { it.startsWith("funko::") && it.length in 14..60 }
            val quickScan = intent?.action == "com.funkodex.ACTION_QUICK_SCAN"

            FunkoDexTheme(appTheme = currentTheme) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    FunkoDexNavHost(deepLinkItemId = deepLinkItemId, openScannerOnStart = quickScan)
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
