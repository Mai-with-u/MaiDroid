package org.maiwithu.maidroid.ui.platforms

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted

// 消息平台页的卡片：上方“已安装”横排的小卡片，下方“发现”网格里的平台卡片，以及它们共用的图标、徽标、标签。

/** 已安装平台的小卡片：图标、名称、状态点和状态文字，右上角齿轮打开该平台的配置弹窗。 */
@Composable
internal fun InstalledPlatformCard(
    platform: InstalledPlatform,
    onSettingsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(170.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
    ) {
        PlatformLogo(
            iconRes = platform.iconRes,
            contentDescription = platform.name,
            color = platform.iconColor,
            modifier = Modifier
                .offset(x = 12.dp, y = 12.dp)
                .size(52.dp),
            cornerRadius = 12.dp,
            iconSize = 32.dp
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSettingsClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = TextMuted,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = platform.name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,
            modifier = Modifier
                .offset(x = 12.dp, y = 72.dp)
                .width(130.dp)
        )

        Row(
            modifier = Modifier
                .offset(x = 12.dp, y = 96.dp)
                .width(142.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(platform.statusColor)
            )

            Spacer(modifier = Modifier.width(5.dp))

            Text(
                text = platform.account,
                color = platform.statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
                maxLines = 1
            )
        }
    }
}

/** 还没有任何已安装平台时的占位卡片。 */
@Composable
internal fun EmptyInstalledPlatformCard() {
    Column(
        modifier = Modifier
            .width(170.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无消息平台",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "安装并登录消息平台后会在这里显示状态",
            color = TextFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 15.sp
        )
    }
}

/** 当前分类下没有平台时的占位卡片。 */
@Composable
internal fun EmptyPlatformCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            .background(CardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无其他平台",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "后续适配项会出现在这里。",
            color = TextFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
    }
}

@Composable
internal fun PlatformLogo(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    color: Color,
    modifier: Modifier,
    cornerRadius: Dp,
    iconSize: Dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun Badge(text: String) {
    Box(
        modifier = Modifier
            .height(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(BrandOrange.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp
        )
    }
}

@Composable
internal fun SmallTag(text: String) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBorder)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 11.sp
        )
    }
}
