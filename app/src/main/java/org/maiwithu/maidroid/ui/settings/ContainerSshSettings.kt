package org.maiwithu.maidroid.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.ssh.ContainerSshPhase
import org.maiwithu.maidroid.ssh.ContainerSshState
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.component.MaiDialogTextField
import org.maiwithu.maidroid.ui.theme.IconMintGreen
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

// 设置页“容器 SSH”一行用到的弹窗、状态文案和同步动作。

/** 设置 sshd 端口和 root 密码；已配置过时密码可留空表示不改。端口限定 1024~65535。 */
@Composable
internal fun ContainerSshConfigDialog(
    portText: String,
    passwordText: String,
    configured: Boolean,
    onPortTextChange: (String) -> Unit,
    onPasswordTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (port: Int, password: String?) -> Unit
) {
    val parsedPort = portText.toIntOrNull()
    val portError = when {
        parsedPort == null -> "端口需要是数字"
        parsedPort !in 1024..65535 -> "端口范围为 1024-65535"
        else -> null
    }
    val passwordError = if (!configured && passwordText.isBlank()) "root 密码不能为空" else null
    val canConfirm = portError == null && passwordError == null

    MaiDialog(
        title = "容器 SSH 设置",
        subtitle = "通过电脑终端连接 Debian 容器管理文件或调试",
        icon = FlatFeatureIcons.ContainerSsh,
        iconBackground = IconMintGreen,
        size = MaiDialogSize.Expanded,
        secondaryContent = {
            MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (configured) {
                        "如果只是改端口或忘记是否改密码，密码框留空即可保持现有 root 密码。"
                    } else {
                        "首次启用前请先设置 1024-65535 之间的端口和 root 登录密码。"
                    },
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
                if (!canConfirm && (portError == null || passwordError == null)) {
                    Text(
                        text = "请先修正左侧提示的问题后保存。",
                        color = StatusWarning,
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                }
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
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val port = parsedPort ?: return@MaiDialogButton
                        onConfirm(port, passwordText.takeIf { it.isNotBlank() })
                    }
                )
            }
        }
    ) {
        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MaiDialogTextField(
                value = portText,
                onValueChange = onPortTextChange,
                label = "端口",
                placeholder = "1024 ~ 65535",
                errorText = portError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            MaiDialogTextField(
                value = passwordText,
                onValueChange = onPasswordTextChange,
                label = "root 密码",
                placeholder = if (configured) "留空则保持当前密码" else "输入 root 密码",
                isPassword = true,
                errorText = passwordError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }
    }
}

private fun containerSshStatusText(state: ContainerSshState): String =
    when {
        !state.configured -> "未配置"
        state.isRunning -> "运行中 · 端口 ${state.port}"
        state.isStarting -> "启动中 · 端口 ${state.port}"
        state.phase == ContainerSshPhase.Failed -> "启动失败 · 端口 ${state.port}"
        state.enabled -> "已开启 · 端口 ${state.port}"
        else -> "已关闭 · 端口 ${state.port}"
    }

/** 状态一行文案；启动失败时第二行附上失败原因和错误码。 */
internal fun containerSshSummaryText(state: ContainerSshState): String {
    val status = containerSshStatusText(state)
    val failure = state.failure?.takeIf {
        state.phase == ContainerSshPhase.Failed && it.message.isNotBlank()
    }
    return if (failure == null) status else "$status\n${failure.message}（${failure.code}）"
}

// 经前台服务按设置同步 sshd，保证切到后台后 SSH 仍由常驻服务托管。
internal fun requestContainerSshSync(context: Context) {
    MaiBotRuntimeService.start(context, MaiBotRuntimeService.ACTION_SYNC_CONTAINER_SSH)
}
