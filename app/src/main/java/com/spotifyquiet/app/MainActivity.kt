package com.spotifyquiet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spotifyquiet.app.monitoring.MonitoringCoordinator
import com.spotifyquiet.app.ui.MainScreen
import com.spotifyquiet.app.ui.theme.SpotifyQuietTheme

/**
 * Single activity. It never stays alive in the background: monitoring lives in
 * the notification listener service and the coordinator object, so closing or
 * rotating this screen does not stop recording.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MonitoringCoordinator.attach(applicationContext)
        setContent {
            SpotifyQuietTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Notification access can be revoked from Settings while we were paused.
        MonitoringCoordinator.refreshPermissionState()
    }
}
