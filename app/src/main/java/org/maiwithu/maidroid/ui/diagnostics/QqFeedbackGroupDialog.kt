package org.maiwithu.maidroid.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.VersionNumber
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.appupdate.AppRelease
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogDivider
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

internal const val QQ_FEEDBACK_GROUP_NAME = "麦麦不吃苹果派"
internal const val QQ_FEEDBACK_GROUP_NUMBER = "750194887"
internal const val QQ_FEEDBACK_GROUP_URL = "https://qm.qq.com/q/8t59i2bhIc"
private const val QQ_GROUP_CARD_SCHEME =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_FEEDBACK_GROUP_NUMBER&card_type=group&source=qrcode"

/**
 * 计算入群验证问题答案所用的 MaiDroid 最新版本号（不带开头的 v，例如 "0.2.4"）：
 * 已从 GitHub Release 拿到最新正式版时取线上版本与当前安装版本中较新的那一个（方便旧版用户拿到最新答案），
 * 离线或尚未查完时回退到当前安装的 versionName。
 */
internal fun resolveLatestAppVersion(currentVersionName: String, release: AppRelease?): String {
    val fallback = currentVersionName.trim().removePrefix("v").removePrefix("V")
    val releaseVersion = release?.version ?: return fallback
    val currentVersion = VersionNumber.parse(fallback) ?: return releaseVersion.toString()
    return maxOf(currentVersion, releaseVersion).toString()
}

/**
 * QQ 用户反馈群弹窗：设置页“帮助 -> QQ 反馈群”以及各处报错提示、反馈问题弹窗里的“QQ 群反馈”共用。
 * 打开时按需静默拉取一次最新正式版信息 -> 展示群名、群号与入群验证答案（MaiDroid 最新版本号）->
 * 点击“跳转 QQ 加群”顺手复制验证答案并打开官方加群链接，方便切到 QQ 后直接粘贴进群。
 */
@Composable
internal fun QqFeedbackGroupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appUpdater = remember(context) { context.appDependencies.appUpdater }
    val appUpdateState by appUpdater.state.collectAsState()
    val latestVersion = resolveLatestAppVersion(BuildConfig.VERSION_NAME, appUpdateState.release)

    // 首次引导阶段报错或刚联网时可能还没查过 Release，打开弹窗时静默补查一次，拿到后自动刷新最新版本号。
    LaunchedEffect(appUpdater) {
        appUpdater.ensureReleaseLoaded()
    }

    MaiDialog(
        title = "QQ 用户反馈群",
        subtitle = "$QQ_FEEDBACK_GROUP_NAME · $QQ_FEEDBACK_GROUP_NUMBER",
        icon = FlatFeatureIcons.QqGroup,
        iconBackground = IconSkyBlue,
        size = MaiDialogSize.Expanded,
        secondaryContent = {
            MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "日常使用遇到小问题欢迎进群交流互助；涉及闪退、安装失败等复杂问题，建议在“反馈问题”中生成诊断包并提交到 GitHub Issue。",
                    color = TextMuted,
                    fontSize = TextSizes.Body,
                    lineHeight = 19.sp
                )
                Text(
                    text = "如需填写验证问题，答案即左侧 MaiDroid 最新版本号；点“跳转 QQ 加群”会自动复制答案并打开加群页面。",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
        },
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "关闭",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "跳转 QQ 加群",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = { openQqFeedbackGroup(context, latestVersion) }
                )
            }
        }
    ) {
        // 群号与入群验证答案集中在一张内嵌面板里，点任意一行即可单独复制对应内容
        MaiDialogSectionCard(
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            CopyableGroupInfoRow(
                label = "群聊【$QQ_FEEDBACK_GROUP_NAME】",
                value = QQ_FEEDBACK_GROUP_NUMBER,
                valueColor = TextPrimary,
                actionLabel = "复制群号",
                onClick = {
                    copyPlainText(context, "MaiDroid 反馈群号", QQ_FEEDBACK_GROUP_NUMBER, "群号已复制")
                }
            )

            MaiDialogDivider(horizontalPadding = 12.dp)

            CopyableGroupInfoRow(
                label = "入群验证答案（最新版本号）",
                value = latestVersion,
                valueColor = BrandOrange,
                actionLabel = "复制答案",
                onClick = {
                    copyPlainText(context, "MaiDroid 最新版本号", latestVersion, "验证答案 $latestVersion 已复制")
                }
            )
        }
    }
}

@Composable
private fun CopyableGroupInfoRow(
    label: String,
    value: String,
    valueColor: Color,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
            Text(
                text = value,
                color = valueColor,
                fontFamily = JetBrainsMono,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
        }
        Text(
            text = actionLabel,
            color = TextPrimary,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Corners.Tag))
                .background(CardBorder)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

private fun copyPlainText(context: Context, clipLabel: String, text: String, toastMessage: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
    // Android 13 起系统剪贴板自带可视化提示，这里只在不弹系统浮层的旧系统上弹 Toast 避免叠两层
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}

// 先复制最新版本号方便填验证问题 -> 打开腾讯官方加群链接（带分享签名，兼容未开启群号搜索的情况） ->
// 设备没有浏览器时回退唤起手机 QQ 群资料卡 -> 仍打不开则复制群号提示用户手动搜索。
private fun openQqFeedbackGroup(context: Context, latestVersion: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("MaiDroid 最新版本号", latestVersion))

    val opened = launchViewUri(context, QQ_FEEDBACK_GROUP_URL) || launchViewUri(context, QQ_GROUP_CARD_SCHEME)
    if (!opened) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("MaiDroid 反馈群号", QQ_FEEDBACK_GROUP_NUMBER))
        Toast.makeText(
            context,
            "未找到浏览器或 QQ，已复制群号 $QQ_FEEDBACK_GROUP_NUMBER（验证答案：$latestVersion）",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        "验证答案 $latestVersion 已复制，如需验证可直接粘贴"
    } else {
        "已复制验证答案 $latestVersion，正在打开加群页面"
    }
    Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
}

private fun launchViewUri(context: Context, uriString: String): Boolean =
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uriString.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
