package org.maiwithu.maidroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

@Composable
fun OobeDownloadScreen(
    modifier: Modifier = Modifier
) {
    OobeFlowScreen(
        currentStep = 1,
        modifier = modifier
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 800)
@Composable
private fun OobeDownloadScreenPreview() {
    MaiDroidTheme {
        OobeDownloadScreen()
    }
}
