package org.maiwithu.maidroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maiwithu.maidroid.ui.screen.OobeFlowScreen
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

class MainActivity : ComponentActivity() {
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        Toast.makeText(
            this,
            if (granted) "存储权限已授权" else "存储权限未授权",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaiDroidTheme {
                var oobeStep by remember { mutableIntStateOf(0) }

                BackHandler(enabled = oobeStep > 0) {
                    oobeStep -= 1
                }

                OobeFlowScreen(
                    currentStep = oobeStep,
                    onStorageAuthorize = {
                        requestStoragePermission()
                    },
                    onBackgroundAuthorize = {
                        requestIgnoreBatteryOptimizations()
                    },
                    onNext = {
                        oobeStep = 1
                    }
                )
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "存储权限已授权", Toast.LENGTH_SHORT).show()
                return
            }

            val packageUri = Uri.parse("package:$packageName")
            val appSettingsIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                packageUri
            )
            val allFilesSettingsIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

            startSettingsActivity(appSettingsIntent, allFilesSettingsIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            Toast.makeText(this, "存储权限已授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "后台权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "后台权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri
        )
        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        startSettingsActivity(requestIntent, settingsIntent)
    }

    private fun startSettingsActivity(primaryIntent: Intent, fallbackIntent: Intent) {
        try {
            startActivity(primaryIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(fallbackIntent)
        }
    }
}
