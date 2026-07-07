package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsPage(
    webUiOnline: Boolean,
    versionName: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface),
        contentPadding = PaddingValues(top = 72.dp, start = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "设置",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            SettingsInfoCard(
                title = "WebUI 状态",
                value = if (webUiOnline) "在线" else "离线",
                accent = if (webUiOnline) PlatformOnline else HomeOffline
            )
        }

        item {
            SettingsInfoCard(
                title = "应用版本",
                value = "v$versionName",
                accent = PlatformOrange
            )
        }

        item {
            SettingsInfoCard(
                title = "消息平台",
                value = "${InstalledPlatforms.size} 个平台正在运行",
                accent = PlatformOnline
            )
        }
    }
}

@Composable
private fun SettingsInfoCard(
    title: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(accent)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PlatformTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        }
    }
}
