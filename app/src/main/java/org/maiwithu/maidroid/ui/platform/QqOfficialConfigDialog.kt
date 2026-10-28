package org.maiwithu.maidroid.ui.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.qqofficial.qqOfficialStatusText
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "QQ 官方机器人",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.installed) {
                    ConfigValueRow(
                        label = "连接状态",
                        value = qqOfficialStatusDetail(state),
                        valueColor = qqOfficialStatusColor(state)
                    )
                }

                state.errorMessage?.let { error ->
                    Text(
                        text = "安装失败：$error",
                        color = StatusDanger,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                }

                OutlinedTextField(
                    value = appIdText,
                    onValueChange = { appIdText = it.trim() },
                    singleLine = true,
                    label = { Text("AppID") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = appSecretText,
                    onValueChange = { appSecretText = it.trim() },
                    singleLine = true,
                    label = { Text("AppSecret") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                Text(
                    text = "在 QQ 开放平台（q.qq.com）创建机器人并开通群聊、单聊能力，" +
                        "把开发设置里的 AppID 和 AppSecret 填到这里。\n" +
                        "IP 白名单请留空：手机网络的 IP 会变，填了容易被拦。\n" +
                        "群里要 @机器人 才会收到消息。确认后会重启 MaiBot，期间所有平台暂停收发消息。",
                    color = TextFaint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
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
                onClick = { onConfirm(appIdText, appSecretText) },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White,
                    disabledContainerColor = CardBorder,
                    disabledContentColor = TextFaint
                )
            ) {
                Text(
                    text = if (state.installed) "保存" else "安装并启用",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
}

// 弹窗里比卡片多说一句：连接失败带上原因，“已启用”说明是按没有报错推断的。
private fun qqOfficialStatusDetail(state: QqOfficialPlatformState): String {
    val summary = qqOfficialStatusText(state)
    return when {
        state.connectionError != null -> "$summary：${state.connectionError}"
        state.presumedOnline -> "$summary，近一分钟没有连接报错"
        else -> summary
    }
}
