package org.maiwithu.maidroid.ui.oobe

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.BrandOrangeMuted
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeTextPrimary
import org.maiwithu.maidroid.ui.theme.OobeTextSecondary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** OOBE 里的单项权限卡片：图标、名称、必须/可选标签、说明和“去授权”按钮，授予后整体变暗。 */
@Composable
internal fun OobePermissionCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    required: Boolean,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false
) {
    val cardColor = if (granted) OobeSurface.copy(alpha = 0.62f) else OobeSurface
    val iconBackground = if (granted) Color.White.copy(alpha = 0.12f) else BrandOrangeMuted
    val primaryTextColor = if (granted) OobeTextSecondary else OobeTextPrimary
    val secondaryTextColor = if (granted) OobeTextSecondary.copy(alpha = 0.72f) else OobeTextSecondary
    val badgeBackground = if (granted) Color.White.copy(alpha = 0.1f) else BrandOrangeMuted

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(22.dp))
            .background(cardColor)
            .padding(horizontal = 17.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(28.dp),
                    tint = if (granted) OobeTextSecondary else Color.White
                )
            }

            Spacer(modifier = Modifier.width(15.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = TextSizes.Title,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        maxLines = 1,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (required) "必须" else "可选",
                        fontSize = TextSizes.Tag,
                        fontWeight = FontWeight.Bold,
                        color = if (granted) OobeTextSecondary else OnBrandOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(badgeBackground)
                            .padding(horizontal = 6.dp, vertical = 0.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    color = secondaryTextColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onAuthorize,
                enabled = !granted,
                modifier = Modifier.size(width = 64.dp, height = 28.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrangeMuted,
                    contentColor = OnBrandOrange,
                    disabledContainerColor = Color.White.copy(alpha = 0.12f),
                    disabledContentColor = OobeTextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (granted) "已授权" else "去授权",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun OobePermissionCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.background(Color(0xFF121212)).padding(24.dp)) {
            OobePermissionCard(
                iconRes = R.drawable.ic_storage_hdd,
                title = "存储权限",
                description = "MaiSaka 需要存储权限来安装、运行和保存配置文件",
                required = true,
                granted = true,
                onAuthorize = {}
            )
        }
    }
}
