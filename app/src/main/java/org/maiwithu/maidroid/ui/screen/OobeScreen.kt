package org.maiwithu.maidroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

@Composable
fun OobeScreen(
    storagePermissionGranted: Boolean = false,
    notificationPermissionGranted: Boolean = false,
    batteryOptimizationGranted: Boolean = false,
    onStorageAuthorize: () -> Unit = {},
    onNotificationAuthorize: () -> Unit = {},
    onBatteryOptimizationAuthorize: () -> Unit = {},
    onAutoStartAuthorize: () -> Unit = {},
    onTaskLockAuthorize: () -> Unit = {},
    onAccessibilityAuthorize: () -> Unit = {},
    onDeviceAdminAuthorize: () -> Unit = {},
    onNext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    OobeFlowScreen(
        currentStep = 0,
        storagePermissionGranted = storagePermissionGranted,
        notificationPermissionGranted = notificationPermissionGranted,
        batteryOptimizationGranted = batteryOptimizationGranted,
        onStorageAuthorize = onStorageAuthorize,
        onNotificationAuthorize = onNotificationAuthorize,
        onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
        onAutoStartAuthorize = onAutoStartAuthorize,
        onTaskLockAuthorize = onTaskLockAuthorize,
        onAccessibilityAuthorize = onAccessibilityAuthorize,
        onDeviceAdminAuthorize = onDeviceAdminAuthorize,
        onNext = onNext,
        modifier = modifier
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 800)
@Composable
private fun OobeScreenPreview() {
    MaiDroidTheme {
        OobeScreen(storagePermissionGranted = true)
    }
}
