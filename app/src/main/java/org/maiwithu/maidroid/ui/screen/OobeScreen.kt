package org.maiwithu.maidroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

@Composable
fun OobeScreen(
    storagePermissionGranted: Boolean = false,
    backgroundPermissionGranted: Boolean = false,
    onStorageAuthorize: () -> Unit = {},
    onBackgroundAuthorize: () -> Unit = {},
    onNext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    OobeFlowScreen(
        currentStep = 0,
        storagePermissionGranted = storagePermissionGranted,
        backgroundPermissionGranted = backgroundPermissionGranted,
        onStorageAuthorize = onStorageAuthorize,
        onBackgroundAuthorize = onBackgroundAuthorize,
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
