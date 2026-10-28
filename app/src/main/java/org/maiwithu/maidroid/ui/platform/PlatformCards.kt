package org.maiwithu.maidroid.ui.platform

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.platform.napcat.NapCatInstallPhase
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialInstallPhase
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

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
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
    ) {
        PlatformLogo(
            iconRes = platform.iconRes,
            contentDescription = platform.name,
            color = platform.iconColor,
            modifier = Modifier
                .offset(x = 12.dp, y = 12.dp)
                .size(52.dp),
            cornerRadius = Corners.Control,
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
            fontSize = TextSizes.BodyLarge,
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
                fontSize = TextSizes.Caption,
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
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无消息平台",
            color = Color.White,
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "安装并登录消息平台后会在这里显示状态",
            color = TextFaint,
            fontSize = TextSizes.Caption,
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
            .clip(RoundedCornerShape(Corners.Card))
            .border(1.dp, CardBorder, RoundedCornerShape(Corners.Card))
            .background(CardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无其他平台",
            color = Color.White,
            fontSize = TextSizes.TitleLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "后续适配项会出现在这里。",
            color = TextFaint,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PlatformLogo(
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
private fun PlatformBadge(text: String) {
    Box(
        modifier = Modifier
            .height(16.dp)
            .clip(RoundedCornerShape(Corners.Tag))
            .background(BrandOrange.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = TextSizes.Tag,
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp
        )
    }
}

@Composable
private fun SmallTag(text: String) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(Corners.Control))
            .background(CardBorder)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = TextSizes.Tag,
            fontWeight = FontWeight.Bold,
            lineHeight = 11.sp
        )
    }
}

/**
 * “发现”网格里的平台卡片：图标、名称、服务方说明、标签，右上角按钮跟着选中的接入方式显示
 * 下载 / 安装进度 / 设置；有多种接入方式时底部显示选择器。
 */
@Composable
internal fun PlatformInstallCard(
    platform: AvailablePlatform,
    selectedAdapter: PlatformAdapter?,
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState,
    onAdapterSelected: (PlatformAdapter) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onQqOfficialSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
    val hasAdapters = platform.adapters.isNotEmpty()
    val isNapCatTarget = selectedAdapter == PlatformAdapter.NapCat
    val isQqOfficialTarget = selectedAdapter == PlatformAdapter.QqOfficial
    // 右上角按钮跟着选中的适配器显示各自的安装进度；没适配的选项一律显示下载图标。
    val buttonState = when {
        isNapCatTarget -> InstallButtonState(
            installing = napCatState.isInstalling,
            progress = napCatState.progress,
            installed = napCatState.canOpenSettings,
            failed = napCatState.phase == NapCatInstallPhase.Failed
        )
        isQqOfficialTarget -> InstallButtonState(
            installing = qqOfficialState.isInstalling,
            progress = qqOfficialState.progress,
            installed = qqOfficialState.installed,
            failed = qqOfficialState.phase == QqOfficialInstallPhase.Failed
        )
        else -> InstallButtonState()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasAdapters) 172.dp else 120.dp)
            .clip(RoundedCornerShape(Corners.Card))
            .border(1.dp, CardBorder, RoundedCornerShape(Corners.Card))
            .background(CardSurface)
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PlatformLogo(
                iconRes = platform.iconRes,
                contentDescription = platform.name,
                color = platform.iconColor,
                modifier = Modifier.size(64.dp),
                cornerRadius = Corners.Panel,
                iconSize = 32.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 48.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = platform.name,
                        color = Color.White,
                        fontSize = TextSizes.Headline,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 25.sp
                    )

                    platform.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(8.dp))
                        PlatformBadge(text = badge)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 服务方说明跟着选择器走：选官方机器人时不再写“将由 NapCat 提供服务”。
                val provider = platform.adapterProviders[selectedAdapter] ?: platform.provider
                Text(
                    text = "$provider\n${platform.description}",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    modifier = Modifier.height(30.dp)
                )

                Spacer(modifier = Modifier.height(9.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    platform.tags.forEach { tag ->
                        SmallTag(text = tag)
                    }
                }
            }
        }

        DownloadIconButton(
            state = buttonState,
            onClick = {
                when {
                    isQqOfficialTarget -> if (!qqOfficialState.isInstalling) onQqOfficialSettings()
                    !isNapCatTarget -> onUnsupportedInstall()
                    napCatState.isInstalling -> Unit
                    napCatState.canOpenSettings -> onNapCatSettings()
                    else -> onNapCatInstall()
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 3.dp, end = 8.dp)
        )

        if (hasAdapters) {
            AdapterSelector(
                adapters = platform.adapters,
                selectedAdapter = selectedAdapter,
                onAdapterSelected = onAdapterSelected,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(adapterSelectorWidth(platform.adapters.size))
                    .height(42.dp)
            )
        }
    }
}

// 卡片右上角按钮的样子：安装中显示进度环，已安装显示齿轮，否则显示下载图标（上次失败时标红）。
private data class InstallButtonState(
    val installing: Boolean = false,
    val progress: Float = 0f,
    val installed: Boolean = false,
    val failed: Boolean = false
)

@Composable
private fun DownloadIconButton(
    state: InstallButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installing = state.installing
    val installed = state.installed
    val failed = state.failed

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            installing -> ProgressRing(progress = state.progress)
            installed -> Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_platform_download),
                contentDescription = "下载",
                tint = if (failed) Color(0xFFFF6B6B) else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(32.dp)) {
        val strokeWidth = 3.dp.toPx()
        drawCircle(
            color = CardBorder,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = BrandOrange,
            startAngle = -90f,
            sweepAngle = 360f * clampedProgress,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}
