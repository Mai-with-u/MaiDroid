package org.maiwithu.maidroid.ui.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.container.ContainerSshManager
import org.maiwithu.maidroid.container.ContainerSshPhase
import org.maiwithu.maidroid.container.ContainerSshState
import org.maiwithu.maidroid.platform.NapCatPlatformManager
import org.maiwithu.maidroid.service.ChatbotService

@Composable
internal fun SettingsPage(
    webUiOnline: Boolean,
    versionName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sshManager = remember(context) { ContainerSshManager.get(context) }
    val sshState by sshManager.state.collectAsState()
    val napCatManager = remember(context) { NapCatPlatformManager.get(context) }
    val napCatState by napCatManager.state.collectAsState()
    val runningPlatformCount = remember(napCatState) {
        installedPlatformCards(napCatState).count { it.running }
    }
    var showSshDialog by remember { mutableStateOf(false) }
    var sshPortText by remember { mutableStateOf(sshState.port.toString()) }
    var sshPasswordText by remember { mutableStateOf("") }

    fun openSshDialog() {
        sshPortText = sshState.port.toString()
        sshPasswordText = ""
        showSshDialog = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MainSurface),
            contentPadding = PaddingValues(top = 72.dp, start = 16.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "设置",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            item {
                SettingsInfoCard(
                    title = "WebUI 状态",
                    value = if (webUiOnline) "在线" else "离线",
                    accent = if (webUiOnline) PlatformOnline else HomeOffline
                )
            }

            item {
                ContainerSshSettingsCard(
                    state = sshState,
                    onConfigureClick = { openSshDialog() },
                    onEnabledChange = { enabled ->
                        if (enabled && !sshState.configured) {
                            Toast.makeText(context, "请先设置端口和 root 密码", Toast.LENGTH_SHORT).show()
                            openSshDialog()
                            return@ContainerSshSettingsCard
                        }

                        sshManager.setEnabled(enabled)
                        if (enabled) {
                            requestContainerSshSync(context)
                        }
                    }
                )
            }

            item {
                SettingsInfoCard(
                    title = "应用版本",
                    value = "v$versionName",
                    accent = PlatformOrange
                )
            }

            item {
                SettingsInfoCard(
                    title = "消息平台",
                    value = if (runningPlatformCount > 0) {
                        "$runningPlatformCount 个平台正在运行"
                    } else {
                        "暂无平台正在运行"
                    },
                    accent = if (runningPlatformCount > 0) PlatformOnline else HomeOffline
                )
            }
        }

        if (showSshDialog) {
            ContainerSshConfigDialog(
                portText = sshPortText,
                passwordText = sshPasswordText,
                configured = sshState.configured,
                onPortTextChange = { value ->
                    sshPortText = value.filter(Char::isDigit).take(5)
                },
                onPasswordTextChange = { sshPasswordText = it },
                onDismiss = { showSshDialog = false },
                onConfirm = { port, password ->
                    sshManager.saveConfig(port, password)
                    showSshDialog = false
                    Toast.makeText(context, "容器 SSH 配置已保存", Toast.LENGTH_SHORT).show()
                    if (sshState.enabled) {
                        requestContainerSshSync(context)
                    }
                }
            )
        }
    }
}

@Composable
private fun ContainerSshSettingsCard(
    state: ContainerSshState,
    onConfigureClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .clickable(onClick = onConfigureClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (state.isRunning) PlatformOnline else HomeOffline)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "开启容器SSH",
                color = PlatformTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = containerSshStatusText(state),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp
            )

            if (state.phase == ContainerSshPhase.Failed && !state.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = state.errorMessage,
                    color = HomeOffline,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = state.enabled,
            onCheckedChange = onEnabledChange,
            enabled = !state.isStarting
        )
    }
}

@Composable
private fun ContainerSshConfigDialog(
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
                fontSize = 20.sp,
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
                        color = HomeOffline,
                        fontSize = 12.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformBorder,
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
                    containerColor = PlatformOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "确定", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = PlatformCardSurface,
        titleContentColor = Color.White,
        textContentColor = PlatformTextSecondary
    )
}

@Composable
private fun SettingsInfoCard(
    title: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(accent)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PlatformTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
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

private fun requestContainerSshSync(context: Context) {
    val intent = Intent(context, ChatbotService::class.java).apply {
        action = ChatbotService.ACTION_SYNC_CONTAINER_SSH
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
