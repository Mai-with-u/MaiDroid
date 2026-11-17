package org.maiwithu.maidroid.ui.oobe

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.theme.BrandOrangeMuted
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.OobeBackground
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeTextPrimary
import org.maiwithu.maidroid.ui.theme.OobeTextSecondary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** OOBE 里的单项权限卡片：清新扁平图标、名称、必须/可选标签、说明和“去授权”按钮，授予后整体变暗。 */
@Composable
internal fun OobePermissionCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    required: Boolean,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false
) {
    val cardColor = if (granted) OobeSurface.copy(alpha = 0.62f) else OobeSurface
    val primaryTextColor = if (granted) OobeTextSecondary else OobeTextPrimary
    val secondaryTextColor = if (granted) OobeTextSecondary.copy(alpha = 0.72f) else OobeTextSecondary
    val badgeBackground = if (granted) OobeTextPrimary.copy(alpha = 0.1f) else BrandOrangeMuted

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Corners.Chip))
            .background(cardColor)
            .padding(horizontal = 17.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatFeatureIconBadge(
                icon = icon,
                backgroundColor = iconColor,
                size = 46.dp,
                iconSize = 30.dp,
                dimmed = granted
            )

            Spacer(modifier = Modifier.width(15.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = TextSizes.Title,
                        fontWeight = FontWeight.Medium,
                        color = primaryTextColor,
                        maxLines = 1,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (required) "必须" else "可选",
                        fontSize = TextSizes.Tag,
                        fontWeight = FontWeight.Medium,
                        color = if (granted) OobeTextSecondary else OnBrandOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corners.Tag))
                            .background(badgeBackground)
                            .padding(horizontal = 6.dp, vertical = 0.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Normal,
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
                shape = RoundedCornerShape(Corners.Control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrangeMuted,
                    contentColor = OnBrandOrange,
                    disabledContainerColor = OobeTextPrimary.copy(alpha = 0.12f),
                    disabledContentColor = OobeTextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (granted) "已授权" else "去授权",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun OobePermissionCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.background(OobeBackground).padding(24.dp)) {
            OobePermissionCard(
                icon = FlatFeatureIcons.Storage,
                iconColor = IconSkyBlue,
                title = "存储权限",
                description = "MaiSaka 需要存储权限来安装、运行和保存配置文件",
                required = true,
                granted = true,
                onAuthorize = {}
            )
        }
    }
}
