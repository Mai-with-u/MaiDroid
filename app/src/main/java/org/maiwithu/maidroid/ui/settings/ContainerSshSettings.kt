package org.maiwithu.maidroid.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.ssh.ContainerSshPhase
import org.maiwithu.maidroid.ssh.ContainerSshState
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
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
    val errorText = when {
        parsedPort == null -> "端口需要是数字"
        parsedPort !in 1024..65535 -> "端口范围为 1024-65535"
        !configured && passwordText.isBlank() -> "root 密码不能为空"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "容器 SSH 设置",
                color = Color.White,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortTextChange,
                    singleLine = true,
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = passwordText,
                    onValueChange = onPasswordTextChange,
                    singleLine = true,
                    label = { Text("root 密码") },
                    placeholder = {
                        Text(if (configured) "留空则保持当前密码" else "输入 root 密码")
                    },
                    visualTransformation = PasswordVisualTransformation()
                )

                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = StatusWarning,
                        fontSize = TextSizes.Caption,
                        lineHeight = 14.sp
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = parsedPort ?: return@Button
                    onConfirm(port, passwordText.takeIf { it.isNotBlank() })
                },
                enabled = errorText == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "确定", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
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

/** 状态一行文案；启动失败时第二行附上失败原因。 */
internal fun containerSshSummaryText(state: ContainerSshState): String {
    val status = containerSshStatusText(state)
    val error = state.errorMessage?.takeIf {
        state.phase == ContainerSshPhase.Failed && it.isNotBlank()
    }
    return if (error == null) status else "$status\n$error"
}

// 经前台服务按设置同步 sshd，保证切到后台后 SSH 仍由常驻服务托管。
internal fun requestContainerSshSync(context: Context) {
    MaiBotRuntimeService.start(context, MaiBotRuntimeService.ACTION_SYNC_CONTAINER_SSH)
}
