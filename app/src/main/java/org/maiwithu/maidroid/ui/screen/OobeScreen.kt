package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.OobeStepIndicator
import org.maiwithu.maidroid.ui.component.PermissionCard
import org.maiwithu.maidroid.ui.theme.BackgroundDark
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.Orange500
import org.maiwithu.maidroid.ui.theme.TextOnOrange
import org.maiwithu.maidroid.ui.theme.TextPrimary

@Composable
fun OobeScreen(
    onStorageAuthorize: () -> Unit = {},
    onBackgroundAuthorize: () -> Unit = {},
    onNext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // ── Header ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_mascot),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Scrollable content ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .offset(y = (-24).dp)  // overlap header so rounded corners are visible
                .clip(RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp))
                .background(BackgroundDark)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            OobeStepIndicator(currentStep = 0, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "授予权限",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Orange500
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "MaiSaka需要以下权限才能运行~\n可选权限也可以不给，如果你确定你在做什么的话",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            PermissionCard(
                iconRes = R.drawable.ic_storage_hdd,
                title = "存储权限",
                description = "MaiSaka需要存储权限才能安装，运行，和保存配置文件",
                required = true,
                onAuthorize = onStorageAuthorize
            )

            Spacer(modifier = Modifier.height(20.dp))

            PermissionCard(
                iconRes = R.drawable.ic_job_run,
                title = "后台权限",
                description = "MaiSaka需要后台权限才能在切换到其他应用时保持后台运行",
                required = false,
                onAuthorize = onBackgroundAuthorize
            )

            Spacer(modifier = Modifier.height(32.dp))
        }

        // ── Bottom button ─────────────────────────────────────────
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .height(64.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange400,
                contentColor = TextOnOrange
            )
        ) {
            Text(
                text = "下一步",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 800)
@Composable
private fun OobeScreenPreview() {
    MaiDroidTheme {
        OobeScreen()
    }
}
