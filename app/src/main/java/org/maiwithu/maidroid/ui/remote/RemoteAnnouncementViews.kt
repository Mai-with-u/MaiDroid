package org.maiwithu.maidroid.ui.remote

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.appupdate.AppUpdatePhase
import org.maiwithu.maidroid.remote.AnnouncementLevel
import org.maiwithu.maidroid.remote.RemoteAnnouncement
import org.maiwithu.maidroid.ui.cleanup.StorageCleanupDialog
import org.maiwithu.maidroid.ui.diagnostics.QqFeedbackGroupDialog
import org.maiwithu.maidroid.ui.settings.AppUpdateDialogHost
import org.maiwithu.maidroid.ui.settings.MaiBotUpdateDialogHost
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 嵌在设置页与首次引导页顶部的远程公告横幅区：
 * 自带订阅生效公告、处理关闭状态以及托管点击动作触发的更新/清理/反馈群弹窗，调用处只需放一行，无需额外传参。
 */
@Composable
internal fun AnnouncementBannerSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val remoteOps = remember(context) { context.appDependencies.remoteOps }
    val banners by remoteOps.bannerAnnouncements.collectAsState()
    var activeModalAction by rememberSaveable { mutableStateOf<String?>(null) }

    if (banners.isNotEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            banners.forEach { ann ->
                AnnouncementBannerCard(
                    announcement = ann,
                    onAction = { targetRaw ->
                        dispatchRemoteAction(context, targetRaw) { modalKey -> activeModalAction = modalKey }
                    },
                    onDismiss = { remoteOps.dismissAnnouncement(ann) }
                )
            }
        }
    }

    RemoteActionDialogHost(activeModalKey = activeModalAction, onDismiss = { activeModalAction = null })
}

@Composable
private fun AnnouncementBannerCard(
    announcement: RemoteAnnouncement,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = if (announcement.level == AnnouncementLevel.Warning) StatusWarning else IconSkyBlue
    val parsedTarget = remember(announcement.actionTarget) { parseRemoteActionTarget(announcement.actionTarget) }
    val actionText = announcement.actionLabel?.takeIf { it.isNotBlank() }
        ?: parsedTarget?.let(::defaultRemoteActionLabel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = announcement.title,
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f)
            )
            if (announcement.dismissible) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭公告",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Text(
            text = announcement.body,
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp,
            modifier = Modifier.padding(end = 8.dp)
        )

        if (parsedTarget != null && actionText != null && announcement.actionTarget != null) {
            Text(
                text = actionText,
                color = BrandOrange,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold,
                lineHeight = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Corners.Tag))
                    .background(BrandOrange.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClickLabel = actionText) {
                        onAction(announcement.actionTarget)
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

/**
 * 挂在根界面 MaiDroidApp 的模态公告弹窗：承接 blocker 级紧急提醒，以及用户点击系统通知栏进入应用时聚焦的公告。
 */
@Composable
internal fun AnnouncementModalDialogHost(
    announcement: RemoteAnnouncement,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activeModalAction by rememberSaveable { mutableStateOf<String?>(null) }
    val parsedTarget = remember(announcement.actionTarget) { parseRemoteActionTarget(announcement.actionTarget) }
    val actionLabel = announcement.actionLabel?.takeIf { it.isNotBlank() }
        ?: parsedTarget?.let(::defaultRemoteActionLabel)

    if (activeModalAction == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = announcement.title,
                    color = TextPrimary,
                    fontSize = TextSizes.TitleLarge,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = announcement.body,
                        color = TextMuted,
                        fontSize = TextSizes.Body,
                        lineHeight = 19.sp
                    )
                }
            },
            dismissButton = if (parsedTarget != null) {
                {
                    Button(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CardBorder, contentColor = TextPrimary)
                    ) {
                        Text(text = "知道了", fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                null
            },
            confirmButton = {
                if (parsedTarget != null && actionLabel != null && announcement.actionTarget != null) {
                    Button(
                        onClick = {
                            dispatchRemoteAction(context, announcement.actionTarget) { modalKey ->
                                activeModalAction = modalKey
                            }
                            if (parsedTarget is RemoteActionTarget.WebUrl || parsedTarget is RemoteActionTarget.AppPermissions) {
                                onDismiss()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = OnBrandOrange)
                    ) {
                        Text(text = actionLabel, fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = OnBrandOrange)
                    ) {
                        Text(text = "知道了", fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = CardSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextMuted
        )
    }

    RemoteActionDialogHost(
        activeModalKey = activeModalAction,
        onDismiss = {
            activeModalAction = null
            onDismiss()
        }
    )
}

/** 执行远端公告或排障热规则指定的跳转动作；属于应用内弹窗的返回弹窗标识交给宿主展示。 */
internal fun dispatchRemoteAction(
    context: Context,
    rawTarget: String?,
    onOpenInternalModal: (String) -> Unit
) {
    when (val target = parseRemoteActionTarget(rawTarget)) {
        RemoteActionTarget.AppUpdate -> {
            val updater = context.appDependencies.appUpdater
            val phase = updater.state.value.phase
            if (phase == AppUpdatePhase.Idle || phase == AppUpdatePhase.UpToDate || phase == AppUpdatePhase.Failed) {
                updater.checkForUpdate()
            }
            onOpenInternalModal("app_update")
        }
        RemoteActionTarget.MaiBotUpdate -> onOpenInternalModal("maibot_update")
        RemoteActionTarget.Cleanup -> onOpenInternalModal("cleanup")
        RemoteActionTarget.QqGroup -> onOpenInternalModal("qq_group")
        RemoteActionTarget.AppPermissions -> {
            val uri = "package:${context.packageName}".toUri()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(context, "无法打开应用详情设置页", Toast.LENGTH_SHORT).show()
            }
        }
        is RemoteActionTarget.WebUrl -> {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, target.httpsUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(context, "未找到可以打开链接的浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        null -> Unit
    }
}

@Composable
internal fun RemoteActionDialogHost(activeModalKey: String?, onDismiss: () -> Unit) {
    when (activeModalKey) {
        "app_update" -> AppUpdateDialogHost(onDismiss = onDismiss)
        "maibot_update" -> MaiBotUpdateDialogHost(onDismiss = onDismiss)
        "cleanup" -> StorageCleanupDialog(onDismiss = onDismiss)
        "qq_group" -> QqFeedbackGroupDialog(onDismiss = onDismiss)
    }
}
