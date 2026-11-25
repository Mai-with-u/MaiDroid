package org.maiwithu.maidroid.ui.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.qqofficial.qqOfficialStatusText
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogCallout
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.component.MaiDialogTextField
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 已启用标绿，有连接报错标红，其余（未安装、待填写、等待 MaiBot）标灰。 */
internal fun qqOfficialStatusColor(state: QqOfficialPlatformState): Color = when {
    state.presumedOnline -> StatusOnline
    state.connectionError != null -> StatusDanger
    else -> TextMuted
}

/**
 * QQ 官方机器人的凭据弹窗，安装前和安装后共用：
 * 未安装时确认按钮是“安装并启用”，已安装时是“保存”（凭据变了才会重启 MaiBot）。
 */
@Composable
internal fun QqOfficialConfigDialog(
    state: QqOfficialPlatformState,
    onConfirm: (appId: String, appSecret: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 输入内容在 Activity 重建后保留：用户可能切去浏览器从 QQ 开放平台复制 AppSecret 再回来。
    var appIdText by rememberSaveable { mutableStateOf(state.credentials.appId) }
    var appSecretText by rememberSaveable { mutableStateOf(state.credentials.appSecret) }
    val canConfirm = appIdText.isNotBlank() && appSecretText.isNotBlank() && !state.isInstalling

    MaiDialog(
        title = "QQ 官方机器人",
        subtitle = if (state.installed) "配置开放平台凭据并接入 MaiBot" else "填写凭据以启用内置官方适配器",
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconSkyBlue,
        size = MaiDialogSize.Expanded,
        headerBadge = if (state.installed) {
            {
                MaiDialogInfoChip(
                    text = qqOfficialStatusText(state),
                    color = qqOfficialStatusColor(state)
                )
            }
        } else {
            null
        },
        secondaryContent = {
            // 横屏时将三条接入指南并列排在右列，左列专注表单填写。
            MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "配置说明",
                    color = TextPrimary,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Bold
                )
                QqOfficialGuideBullet("在 QQ 开放平台（q.qq.com）创建机器人并开通群聊、单聊能力。")
                QqOfficialGuideBullet("IP 白名单请留空：手机网络的出口 IP 会变化，填了容易被拦截。")
                QqOfficialGuideBullet("群里需要 @机器人 才会收到消息；确认后会重启 MaiBot，期间暂不收发。")
                QqOfficialGuideBullet("息屏后若频繁 4009 掉线，可在设置页开启「Wi-Fi 低延迟保活」、忽略电池优化，并避免同一 AppID 多端同时登录。")
            }
        },
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = if (state.installed) "保存" else "安装并启用",
                    style = MaiDialogButtonStyle.Primary,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfirm(appIdText, appSecretText) }
                )
            }
        }
    ) {
        if (state.installed && state.connectionError != null) {
            MaiDialogCallout(
                title = "连接失败",
                body = state.connectionError,
                accentColor = StatusDanger
            )
        }
        if (state.installed && state.connectionError == null && state.frequentTimeoutHint != null) {
            MaiDialogCallout(
                title = "频繁会话超时（4009）",
                body = state.frequentTimeoutHint,
                accentColor = StatusWarning
            )
        }

        state.failure?.let { failure ->
            FailureNotice(failure = failure, summary = failure.message)
        }

        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MaiDialogTextField(
                value = appIdText,
                onValueChange = { appIdText = it.trim() },
                label = "AppID",
                placeholder = "QQ 开放平台机器人 AppID",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            MaiDialogTextField(
                value = appSecretText,
                onValueChange = { appSecretText = it.trim() },
                label = "AppSecret",
                placeholder = "QQ 开放平台机器人 AppSecret",
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }
    }
}

@Composable
private fun QqOfficialGuideBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "•",
            color = BrandOrange,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
