package org.maiwithu.maidroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

@Preview(
    name = "Home / Online",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOnlinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Offline",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOfflinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Tall Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 360,
    heightDp = 900
)
@Composable
private fun HomeScreenTallRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Short Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 760
)
@Composable
private fun HomeScreenShortRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}
