package org.maiwithu.maidroid.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 自定义仓库 / 直链安装的安全风险警告框：
 * 用户输入 fork 或第三方链接时无法与原官方包对齐 SHA-256，必须在安装与重装确认前醒目提示泄露和投毒风险。
 */
@Composable
internal fun CustomSourceRiskNotice(
    clientLabel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Control))
            .background(StatusDanger.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "安全风险提示",
            color = StatusDanger,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
        Text(
            text = "自定义来源的 $clientLabel 安装包无法保证与原官方发行版 SHA-256 一致。" +
                "SHA-256 不一致可能代表代码已被修改，存在 QQ 账号、聊天记录等个人信息泄露或恶意投毒行为；" +
                "继续操作即代表你已了解风险并自行承担后果。",
            color = TextPrimary.copy(alpha = 0.9f),
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp
        )
    }
}

/**
 * 自定义仓库或压缩包直链输入框：输入非空时实时校验格式和扩展名，错误时直接在下方给出具体原因。
 */
@Composable
internal fun CustomSourceTextField(
    client: QqClient,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val labelText = when (client) {
        QqClient.NapCat -> "仓库地址或 .zip 直链"
        QqClient.SnowLuma -> "仓库地址或 .tar.gz 直链"
    }
    val placeholderText = when (client) {
        QqClient.NapCat -> "https://github.com/owner/NapCatQQ"
        QqClient.SnowLuma -> "https://github.com/owner/SnowLuma"
    }
    val parseError = if (value.isNotBlank()) {
        (parseQqCustomSource(client, value) as? QqSourceParseResult.Invalid)?.reason
    } else {
        null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = parseError != null,
            label = { Text(text = labelText) },
            placeholder = { Text(text = placeholderText, color = TextFaint) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(Corners.Control),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = BrandOrange,
                focusedBorderColor = BrandOrange,
                unfocusedBorderColor = CardBorder,
                errorBorderColor = StatusDanger,
                focusedLabelColor = BrandOrange,
                unfocusedLabelColor = TextMuted,
                errorLabelColor = StatusDanger,
                focusedContainerColor = PageBackground,
                unfocusedContainerColor = PageBackground,
                errorContainerColor = PageBackground
            )
        )
        if (parseError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = parseError,
                color = StatusDanger,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * 已安装协议端配置弹窗内的“自定义安装包来源”折叠区：
 * 默认收起以保持首屏简洁，展开后允许保存新地址或一行触发覆盖重装。
 */
@Composable
internal fun CustomSourceEditorSection(
    client: QqClient,
    savedUrl: String,
    installing: Boolean,
    onSaveUrl: (String) -> Unit,
    onRequestReinstall: (String) -> Unit
) {
    var draftUrl by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    val parsed = parseQqCustomSource(client, draftUrl)
    val isValid = parsed is QqSourceParseResult.Valid
    val normalizedDraft = (parsed as? QqSourceParseResult.Valid)?.source?.rawUrl ?: draftUrl.trim()
    val summaryText = if (savedUrl.isBlank()) "未配置" else savedUrl.removePrefix("https://").removePrefix("http://")

    ConfigFoldableSection(
        title = "自定义安装包来源",
        summary = summaryText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CustomSourceTextField(
                client = client,
                value = draftUrl,
                onValueChange = { draftUrl = it }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSaveUrl(normalizedDraft) },
                    enabled = isValid && normalizedDraft != savedUrl,
                    shape = RoundedCornerShape(Corners.Control),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardBorder,
                        contentColor = TextPrimary,
                        disabledContainerColor = CardBorder.copy(alpha = 0.55f),
                        disabledContentColor = TextFaint
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Text(
                        text = "保存地址",
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = { onRequestReinstall(normalizedDraft) },
                    enabled = isValid && !installing,
                    shape = RoundedCornerShape(Corners.Control),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandOrange.copy(alpha = 0.16f),
                        contentColor = BrandOrange,
                        disabledContainerColor = CardBorder.copy(alpha = 0.55f),
                        disabledContentColor = TextFaint
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Text(
                        text = "覆盖重装",
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 从自定义地址重新安装前的二次确认弹窗：明确告知会暂时停下 QQ 并展示 SHA-256 安全风险提示。 */
@Composable
internal fun CustomSourceReinstallConfirmDialog(
    client: QqClient,
    sourceUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PlatformDialogTitle("重新安装 ${client.label}？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlatformDialogBody(
                    "将从以下地址重新下载并覆盖 ${client.label} 程序文件（保留现有配置与 QQ 登录数据），期间会暂时停止 QQ："
                )
                Text(
                    text = sourceUrl,
                    color = TextPrimary,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
                CustomSourceRiskNotice(clientLabel = client.label)
            }
        },
        dismissButton = { PlatformSecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = { PlatformDangerButton(text = "了解风险并重装", onClick = onConfirm) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}
