package org.maiwithu.maidroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maiwithu.maidroid.ui.screen.OobeScreen
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaiDroidTheme {
                OobeScreen(
                    onStorageAuthorize = {
                        // TODO: Request MANAGE_EXTERNAL_STORAGE permission
                    },
                    onBackgroundAuthorize = {
                        // TODO: Request REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    },
                    onNext = {
                        // TODO: Navigate to OOBE step 2
                    }
                )
            }
        }
    }
}
