package org.maiwithu.maidroid.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.appupdate.AppDownloadProgress
import org.maiwithu.maidroid.appupdate.AppUpdateAction
import org.maiwithu.maidroid.appupdate.AppUpdatePhase
import org.maiwithu.maidroid.appupdate.AppUpdateState
import org.maiwithu.maidroid.appupdate.ReleaseNoteKind
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.appupdate.releaseNoteLines
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 应用更新弹窗主按钮按下后要做的事。 */
internal enum class AppUpdateButtonAction {
    Check,
    Download,
    CancelDownload,
    GrantInstallPermission,
    Install,
    OpenReleasePage,
    None
}

internal data class AppUpdateButton(
    val label: String,
    val action: AppUpdateButtonAction
) {
    val enabled: Boolean get() = action != AppUpdateButtonAction.None
}

/**
 * 弹窗主按钮：检查 -> 下载并安装（有断点时“继续下载”）-> 下载中可取消 -> 安装；
 * 没开“安装未知应用”时先“去开启”；签名和发布版不同的包只给“打开下载页”。失败时按失败的那一步重试。
 */
internal fun appUpdatePrimaryButton(state: AppUpdateState, installPermissionGranted: Boolean): AppUpdateButton {
    fun installButton() = if (installPermissionGranted) {
        AppUpdateButton("安装", AppUpdateButtonAction.Install)
    } else {
        AppUpdateButton("去开启", AppUpdateButtonAction.GrantInstallPermission)
    }

    fun downloadButton() = when {
        !state.canInstallInApp -> AppUpdateButton("打开下载页", AppUpdateButtonAction.OpenReleasePage)
        state.partialBytes > 0 -> AppUpdateButton("继续下载", AppUpdateButtonAction.Download)
        else -> AppUpdateButton("下载并安装", AppUpdateButtonAction.Download)
    }

    return when (state.phase) {
        AppUpdatePhase.Checking -> AppUpdateButton("检查中...", AppUpdateButtonAction.None)
        AppUpdatePhase.Downloading -> AppUpdateButton("取消下载", AppUpdateButtonAction.CancelDownload)
        AppUpdatePhase.Verifying -> AppUpdateButton("校验中...", AppUpdateButtonAction.None)
        AppUpdatePhase.Available -> downloadButton()
        AppUpdatePhase.ReadyToInstall -> installButton()
        AppUpdatePhase.Failed -> when (state.failedAction) {
            AppUpdateAction.Download -> if (state.canInstallInApp) {
                AppUpdateButton("重试下载", AppUpdateButtonAction.Download)
            } else {
                downloadButton()
            }
            AppUpdateAction.Install -> installButton()
            AppUpdateAction.Check,
            null -> AppUpdateButton("重新检查", AppUpdateButtonAction.Check)
        }
        AppUpdatePhase.Idle,
        AppUpdatePhase.UpToDate -> AppUpdateButton("检查更新", AppUpdateButtonAction.Check)
    }
}

/** 设置页“应用版本”一行的副标题。 */
internal fun appUpdateSummaryText(state: AppUpdateState): String {
    val current = "v${state.currentVersion}"
    val tag = state.release?.tag.orEmpty()
    return when (state.phase) {
        AppUpdatePhase.Idle -> "$current · 点击检查更新"
        AppUpdatePhase.Checking -> "$current · 正在检查更新..."
        AppUpdatePhase.UpToDate -> "$current · 已是最新"
        AppUpdatePhase.Available -> "$current · 发现新版本 $tag"
        AppUpdatePhase.Downloading -> {
            val percent = state.progress?.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
            "正在下载 $tag$percent"
        }
        AppUpdatePhase.Verifying -> "正在校验 $tag 安装包..."
        AppUpdatePhase.ReadyToInstall -> "$tag 已下载，点击安装"
        AppUpdatePhase.Failed -> "$current · 更新失败：${state.errorMessage.orEmpty()}"
    }
}

/** 下载进度一行：45% · 70.2 MB / 155.6 MB · 2.1 MB/s · 经 gh-proxy.com。还没选出线路时说明正在选。 */
internal fun appDownloadProgressText(progress: AppDownloadProgress): String {
    if (progress.source == null) return "正在同时尝试各条线路，选出最快的一条..."
    val parts = buildList {
        progress.fraction?.let { add("${(it * 100).toInt()}%") }
        add(
            if (progress.totalBytes > 0) {
                "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
            } else {
                formatBytes(progress.downloadedBytes)
            }
        )
        if (progress.bytesPerSecond > 0) add("${formatBytes(progress.bytesPerSecond)}/s")
        add("经 ${progress.source}")
    }
    return parts.joinToString(" · ")
}

/** 弹窗里的状态说明。 */
internal fun appUpdateStatusText(state: AppUpdateState, installPermissionGranted: Boolean): String =
    when (state.phase) {
        AppUpdatePhase.Idle -> "检查更新会同时尝试直连 GitHub 和多个镜像，用最快的那条。"
        AppUpdatePhase.Checking -> "正在同时尝试直连 GitHub 和多个镜像..."
        AppUpdatePhase.UpToDate -> "已是最新版本。"
        AppUpdatePhase.Available -> when {
            !state.canInstallInApp ->
                "当前装的是调试版或自行构建的版本，签名和发布版不同，系统不允许直接覆盖安装。" +
                    "要换成发布版，需要先卸载 MaiDroid（会清掉容器里的 MaiBot 和数据），再安装下载页里的安装包。"
            // 点安装时发现安装包已被系统清理，会退回这里并带上原因，提示用户重新下载
            state.errorMessage != null -> state.errorMessage
            state.partialBytes > 0 ->"上次已下载 ${formatBytes(state.partialBytes)}，继续下载会接着下。"
            else -> "发现新版本。"
        }
        AppUpdatePhase.Downloading -> state.progress?.let(::appDownloadProgressText) ?: "正在下载..."
        AppUpdatePhase.Verifying -> "正在校验安装包..."
        AppUpdatePhase.ReadyToInstall -> if (installPermissionGranted) {
            "安装包已下载并校验通过。"
        } else {
            "安装包已下载。还需要允许 MaiDroid“安装未知应用”，开启后回到这里点安装。"
        }
        AppUpdatePhase.Failed -> state.errorMessage.orEmpty().ifBlank { "更新失败，请查看终端日志。" }
    }

// 下载或安装前提醒：系统安装器会结束 MaiDroid 进程，容器里的进程跟着停。
private const val INSTALL_NOTE =
    "安装时系统会关闭 MaiDroid，麦麦和 NapCat 随之停止；装好后重新打开 MaiDroid 即可恢复，QQ 可能需要重新扫码。"

/**
 * 接上应用更新管理器的更新弹窗：设置页点“应用版本”、启动时查到新版本的提醒共用这一个。
 * 状态都在进程级的更新管理器里，弹窗关掉再打开、Activity 重建后都能接上检查和下载进度。
 * showMaiBotAutoUpdateSwitch 为 true 时（只有设置页点开时）底部多一个“关闭 MaiBot 自动更新”开关：
 * 我们鼓励 MaiBot 保持最新，这个开关不放在设置列表里，也不在启动提醒里出现。
 */
@Composable
internal fun AppUpdateDialogHost(onDismiss: () -> Unit, showMaiBotAutoUpdateSwitch: Boolean = false) {
    val context = LocalContext.current
    val appUpdater = remember(context) { context.appDependencies.appUpdater }
    val settings = remember(context) { context.appDependencies.settings }
    val state by appUpdater.state.collectAsState()
    var installPermissionGranted by remember { mutableStateOf(appUpdater.canRequestPackageInstalls()) }
    var maiBotAutoUpdateDisabled by remember { mutableStateOf(settings.maiBotAutoUpdateDisabled) }

    // 每次回到前台重新读“安装未知应用”：用户从系统设置页开完权限回来，按钮直接变成“安装”。
    LifecycleResumeEffect(appUpdater) {
        installPermissionGranted = appUpdater.canRequestPackageInstalls()
        onPauseOrDispose {}
    }

    AppUpdateDialog(
        state = state,
        installPermissionGranted = installPermissionGranted,
        onCheck = appUpdater::checkForUpdate,
        onDownload = appUpdater::download,
        onCancelDownload = appUpdater::cancelDownload,
        onGrantInstallPermission = appUpdater::openInstallPermissionSettings,
        onInstall = appUpdater::install,
        onOpenReleasePage = {
            // 签名和发布版不同的包不能在应用里覆盖安装，交给浏览器打开 Release 页面
            val pageUrl = state.release?.pageUrl.orEmpty()
            val opened = pageUrl.isNotBlank() && try {
                context.startActivity(Intent(Intent.ACTION_VIEW, pageUrl.toUri()))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
            if (!opened) {
                Toast.makeText(context, "没有找到可以打开下载页的浏览器", Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = onDismiss,
        maiBotAutoUpdateSwitch = if (showMaiBotAutoUpdateSwitch) {
            MaiBotAutoUpdateSwitch(disabled = maiBotAutoUpdateDisabled) { disabled ->
                // 写进设置即生效：下次 MaiDroid 更新后的第一次启动按它决定要不要自动升级 MaiBot
                settings.maiBotAutoUpdateDisabled = disabled
                maiBotAutoUpdateDisabled = disabled
            }
        } else {
            null
        }
    )
}

/** 更新弹窗底部“关闭 MaiBot 自动更新”开关的当前值和切换回调。 */
internal class MaiBotAutoUpdateSwitch(
    val disabled: Boolean,
    val onChange: (Boolean) -> Unit
)

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    installPermissionGranted: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDismiss: () -> Unit,
    maiBotAutoUpdateSwitch: MaiBotAutoUpdateSwitch? = null
) {
    val primaryButton = appUpdatePrimaryButton(state, installPermissionGranted)
    val release = state.release
    // 只有比当前新的版本才展示更新说明；“已是最新”时只显示一行版本号
    val showNewRelease = release != null && state.phase != AppUpdatePhase.UpToDate &&
        state.phase != AppUpdatePhase.Idle && state.phase != AppUpdatePhase.Checking &&
        !(state.phase == AppUpdatePhase.Failed && state.failedAction == AppUpdateAction.Check)
    val showInstallNote = showNewRelease && state.canInstallInApp && (
        state.phase == AppUpdatePhase.Available || state.phase == AppUpdatePhase.ReadyToInstall
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "MaiDroid 更新",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            // 手机横屏时弹窗很矮，内容区可以滚动，不会被截掉；更新说明自己限高滚动，嵌在里面互不影响。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VersionLine(label = "当前版本", value = "v${state.currentVersion}", highlight = false)

                if (showNewRelease) {
                    val details = listOfNotNull(
                        release.tag,
                        release.publishedAtEpochSeconds?.let(::formatReleaseDate),
                        release.apk?.size?.takeIf { it > 0 }?.let(::formatBytes)
                    )
                    VersionLine(label = "最新版本", value = details.joinToString(" · "), highlight = true)
                    ReleaseNotes(release.notes)
                } else if (state.phase == AppUpdatePhase.UpToDate && release != null) {
                    VersionLine(label = "最新版本", value = release.tag, highlight = false)
                }

                UpdateStatus(state, installPermissionGranted)

                if (showInstallNote) {
                    Text(text = INSTALL_NOTE, color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 16.sp)
                }

                maiBotAutoUpdateSwitch?.let { MaiBotAutoUpdateRow(it) }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CardBorder, contentColor = TextPrimary)
            ) {
                Text(text = if (state.isBusy) "后台继续" else "关闭", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (primaryButton.action) {
                        AppUpdateButtonAction.Check -> onCheck()
                        AppUpdateButtonAction.Download -> onDownload()
                        AppUpdateButtonAction.CancelDownload -> onCancelDownload()
                        AppUpdateButtonAction.GrantInstallPermission -> onGrantInstallPermission()
                        AppUpdateButtonAction.Install -> onInstall()
                        AppUpdateButtonAction.OpenReleasePage -> onOpenReleasePage()
                        AppUpdateButtonAction.None -> Unit
                    }
                },
                enabled = primaryButton.enabled,
                colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = TextPrimary)
            ) {
                Text(text = primaryButton.label, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

private fun formatReleaseDate(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

@Composable
private fun VersionLine(label: String, value: String, highlight: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp
        )
        Text(
            text = value,
            color = if (highlight) BrandOrange else TextPrimary,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )
    }
}

// 更新说明来自 CHANGELOG，一版可能有二三十条，限高后在弹窗里单独滚动。
@Composable
private fun ReleaseNotes(notes: String) {
    val lines = releaseNoteLines(notes)
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { line ->
            when (line.kind) {
                ReleaseNoteKind.Heading -> Text(
                    text = line.text,
                    color = TextPrimary,
                    fontSize = TextSizes.Body,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                ReleaseNoteKind.Bullet -> Row(modifier = Modifier.padding(start = (line.indent * 14).dp)) {
                    Text(text = "•  ", color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 16.sp)
                    Text(text = line.text, color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 16.sp)
                }
                ReleaseNoteKind.Text -> Text(
                    text = line.text,
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// 弹窗底部的开关行：左边标题和说明，右边开关，整行可点。默认关闭，即 MaiDroid 更新后第一次启动自动升级 MaiBot。
@Composable
private fun MaiBotAutoUpdateRow(switch: MaiBotAutoUpdateSwitch) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = switch.disabled, role = Role.Switch, onValueChange = switch.onChange)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "关闭 MaiBot 自动更新",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(
                text = if (switch.disabled) {
                    "已关闭，MaiDroid 更新后需要在“MaiBot 更新”里手动更新。"
                } else {
                    "MaiDroid 更新后第一次启动时，自动把 MaiBot 升级到最新。"
                },
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = switch.disabled,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun UpdateStatus(state: AppUpdateState, installPermissionGranted: Boolean) {
    val color = when {
        state.phase == AppUpdatePhase.Failed -> StatusWarning
        state.phase == AppUpdatePhase.Available && !state.canInstallInApp -> StatusWarning
        state.phase == AppUpdatePhase.UpToDate || state.phase == AppUpdatePhase.ReadyToInstall -> StatusOnline
        state.phase == AppUpdatePhase.Available -> BrandOrange
        else -> TextMuted
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.phase == AppUpdatePhase.Downloading) {
            val fraction = state.progress?.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = BrandOrange,
                    trackColor = CardBorder,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(color = BrandOrange, trackColor = CardBorder, modifier = Modifier.fillMaxWidth())
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.phase == AppUpdatePhase.Checking || state.phase == AppUpdatePhase.Verifying) {
                CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            Text(
                text = appUpdateStatusText(state, installPermissionGranted),
                color = color,
                fontSize = TextSizes.Body,
                lineHeight = 17.sp
            )
        }
    }
}
