package org.maiwithu.maidroid.ui.oobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
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
    // 已授权统一降暗；未授权时「必须」用品牌橙高亮、「可选」用柔灰底块与主按钮拉开层级。
    val badgeBackground = when {
        granted -> OobeTextPrimary.copy(alpha = 0.1f)
        required -> BrandOrangeMuted
        else -> OobeTextPrimary.copy(alpha = 0.14f)
    }
    val badgeTextColor = when {
        granted -> OobeTextSecondary
        required -> OnBrandOrange
        else -> OobeTextPrimary.copy(alpha = 0.82f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(cardColor)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatFeatureIconBadge(
                icon = icon,
                backgroundColor = iconColor,
                size = 44.dp,
                iconSize = 28.dp,
                dimmed = granted
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 标题与角标用流式排版：常规屏单行紧贴显示，大字号下角标整体换到下一行而不被挤成竖排。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = TextSizes.BodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        letterSpacing = 0.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corners.Tag))
                            .background(badgeBackground)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (required) "必须" else "可选",
                            fontSize = TextSizes.Tag,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor,
                            maxLines = 1,
                            softWrap = false,
                            letterSpacing = 0.sp,
                            lineHeight = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Normal,
                    color = secondaryTextColor,
                    letterSpacing = 0.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            LiquidButton(
                onClick = onAuthorize,
                enabled = !granted,
                backdrop = rememberSurfaceBackdrop(OobeSurface),
                style = LiquidButtonStyle.Primary,
                shape = { RoundedRectangle(Corners.Control) },
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (granted) "已授权" else "去授权",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    letterSpacing = 0.sp,
                    lineHeight = 14.sp
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
                title = "外部存储权限",
                description = "按需让容器访问手机外部文件（/sdcard），不开启不影响安装与运行。",
                required = false,
                granted = true,
                onAuthorize = {}
            )
        }
    }
}
