package org.maiwithu.maidroid.ui.platform

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 手机上的消息平台页用分组列表，样子和设置页一致：灰色小标题 + 一张圆角卡片，卡片里一行一个平台。
// 平板仍用 PlatformCards 里的大卡片网格。

private val PlatformRowLogoSize = 40.dp
private val PlatformRowHorizontalPadding = 13.dp
private val PlatformRowLogoGap = 11.dp

/**
 * 一组平台：标题行（右侧可放分类按钮等）-> 可选的标题下方内容 -> 圆角卡片。
 * 卡片高度变化（切分类、搜索）时带动画伸缩，不会一下跳变。
 */
@Composable
internal fun PlatformSection(
    title: String,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    belowTitle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .weight(1f)
            )
            titleTrailing?.invoke()
        }

        belowTitle?.invoke()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Card))
                .border(1.dp, CardBorder, RoundedCornerShape(Corners.Card))
                .background(CardSurface)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            content = content
        )
    }
}

/** 卡片里两行之间的分隔线，从文字起点画起，和设置页一样不穿过图标。 */
@Composable
internal fun PlatformRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PlatformRowHorizontalPadding + PlatformRowLogoSize + PlatformRowLogoGap,
                end = 14.dp
            )
            .height(1.dp)
            .background(CardBorder.copy(alpha = 0.72f))
    )
}

/** 已连接的一行：图标、名称、状态点和状态文字；点整行和点齿轮一样，打开这个平台的配置。 */
@Composable
internal fun InstalledPlatformRow(
    platform: InstalledPlatform,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "设置", onClick = onSettingsClick)
            .padding(horizontal = PlatformRowHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlatformLogo(
            iconRes = platform.iconRes,
            contentDescription = null,
            color = platform.iconColor,
            modifier = Modifier.size(PlatformRowLogoSize),
            cornerRadius = Corners.Control,
            iconSize = 24.dp
        )

        Spacer(modifier = Modifier.width(PlatformRowLogoGap))

        Column(modifier = Modifier.weight(1f)) {
            PlatformRowTitle(text = platform.name)

            Spacer(modifier = Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 还没有接入任何平台时，“已连接”卡片里的提示。 */
@Composable
internal fun EmptyInstalledPlatformRow() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        PlatformRowTitle(text = "暂无消息平台")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "安装并登录消息平台后会在这里显示状态",
            color = TextFaint,
            fontSize = TextSizes.Caption,
            lineHeight = 15.sp
        )
    }
}

/** 当前分类和搜索词下一个平台都没有时，“发现”卡片里的提示。 */
@Composable
internal fun EmptyPlatformRow() {
    Text(
        text = "没有找到匹配的平台",
        color = TextMuted,
        fontSize = TextSizes.Body,
        lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

/**
 * “发现”里的一行平台。
 * 已适配的平台：说明写服务方（跟着选中的接入方式变），右侧是下载 / 安装进度 / 设置按钮，
 * 有多种接入方式时选择器排在下面，占满整行宽度，手机竖屏上“官方机器人”五个字也放得下。
 * 没适配的平台：说明写简介，右侧只放灰色“暂未适配”标签，不再给一个点了只会弹提示的下载按钮。
 */
@Composable
internal fun PlatformRow(
    platform: AvailablePlatform,
    selectedAccessMethod: AccessMethod?,
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState,
    onAccessMethodSelected: (AccessMethod) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onQqOfficialSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
    // 有接入方式的平台才算已适配；目前只有 QQ。
    val supported = platform.accessMethods.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlatformRowHorizontalPadding, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformLogo(
                iconRes = platform.iconRes,
                contentDescription = null,
                color = platform.iconColor,
                modifier = Modifier.size(PlatformRowLogoSize),
                cornerRadius = Corners.Control,
                iconSize = 24.dp
            )

            Spacer(modifier = Modifier.width(PlatformRowLogoGap))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformRowTitle(
                        text = platform.name,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    platform.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(6.dp))
                        PlatformBadge(text = badge)
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = if (supported) {
                        platform.accessMethodProviders[selectedAccessMethod] ?: platform.provider
                    } else {
                        platform.description
                    },
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (supported) {
                val installAction = platformInstallAction(
                    selectedAccessMethod = selectedAccessMethod,
                    napCatState = napCatState,
                    qqOfficialState = qqOfficialState,
                    onNapCatInstall = onNapCatInstall,
                    onNapCatSettings = onNapCatSettings,
                    onQqOfficialSettings = onQqOfficialSettings,
                    onUnsupportedInstall = onUnsupportedInstall
                )
                DownloadIconButton(
                    state = installAction.state,
                    onClick = installAction.onClick
                )
            } else {
                UnsupportedPill()
            }
        }

        if (platform.accessMethods.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            AccessMethodSelector(
                accessMethods = platform.accessMethods,
                selectedAccessMethod = selectedAccessMethod,
                onAccessMethodSelected = onAccessMethodSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            )
        }
    }
}

/** 分类小胶囊排成一行：手机竖屏放在“发现”标题下面，横屏放在标题右边。 */
@Composable
internal fun CompactCategoryChips(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.forEach { category ->
            CategoryChip(
                text = category,
                selected = category == selectedCategory,
                onClick = { onCategorySelected(category) },
                compact = true
            )
        }
    }
}

@Composable
private fun PlatformRowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = TextSizes.BodyLarge,
        fontWeight = FontWeight.Bold,
        lineHeight = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun UnsupportedPill() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardBorder)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂未适配",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp
        )
    }
}
