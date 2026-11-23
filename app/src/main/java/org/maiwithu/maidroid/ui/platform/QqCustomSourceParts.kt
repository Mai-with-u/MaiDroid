package org.maiwithu.maidroid.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogCallout
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogTextField
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.TerminalBackground
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
    MaiDialogCallout(
        title = "安全风险提示",
        body = "自定义来源的 $clientLabel 安装包无法保证与原官方发行版 SHA-256 一致。" +
            "SHA-256 不一致可能代表代码已被修改，存在 QQ 账号、聊天记录等个人信息泄露或恶意投毒行为；" +
            "继续操作即代表你已了解风险并自行承担后果。",
        accentColor = StatusDanger,
        modifier = modifier
    )
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

    MaiDialogTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelText,
        placeholder = placeholderText,
        errorText = parseError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = modifier
    )
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
                LiquidButton(
                    onClick = { onSaveUrl(normalizedDraft) },
                    enabled = isValid && normalizedDraft != savedUrl,
                    style = LiquidButtonStyle.Secondary,
                    shape = { RoundedRectangle(Corners.Control) },
                    minHeight = 38.dp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "保存地址",
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                LiquidButton(
                    onClick = { onRequestReinstall(normalizedDraft) },
                    enabled = isValid && !installing,
                    style = LiquidButtonStyle.SoftAccent,
                    shape = { RoundedRectangle(Corners.Control) },
                    minHeight = 38.dp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
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
    MaiDialog(
        title = "重新安装 ${client.label}？",
        subtitle = "保留现有配置与 QQ 登录数据，期间 QQ 暂停",
        icon = FlatFeatureIcons.MaiBotUpdate,
        iconBackground = IconRoseAlert,
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
                    text = "了解风险并重装",
                    style = MaiDialogButtonStyle.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    ) {
        Text(
            text = "将从以下来源重新下载并覆盖 ${client.label} 程序文件：",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
        MaiDialogSectionCard(contentPadding = PaddingValues(12.dp)) {
            Text(
                text = sourceUrl,
                color = TextPrimary,
                fontFamily = JetBrainsMono,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corners.Tag))
                    .background(TerminalBackground)
                    .padding(10.dp)
            )
        }
        CustomSourceRiskNotice(clientLabel = client.label)
    }
}
