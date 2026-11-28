package org.maiwithu.maidroid.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.backup.BackupManager
import org.maiwithu.maidroid.backup.BackupRecord
import org.maiwithu.maidroid.backup.BackupRunOutcome
import org.maiwithu.maidroid.backup.BackupState
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.GlassHeaderCircleButton
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 设置页的「自动备份」详情：支持默认 `/sdcard/MaiDroid/Backups`（按需申请外部存储权限）与自定义 SAF 文件夹，
 * 可配置每日凌晨执行时间、保留份数与备份组件范围（含记忆库），并管理历史备份包。
 */
@Composable
internal fun BackupSettingsPage(
    manager: BackupManager,
    state: BackupState,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    onBack: () -> Unit,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    expandedTopOffset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val statusBarTop = statusBarTopInset()
    val effectiveTopOffset = if (expandedTopOffset > 0.dp) expandedTopOffset else 14.dp
    val horizontalPadding = if (compact) HomeTwoPaneSpec.OuterHorizontalPadding else 20.dp
    val headerTopPadding = statusBarTop + effectiveTopOffset + 56.dp
    val bottomClearance = LocalBottomNavigationClearance.current

    val listState = rememberLazyListState()
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val collapseDistancePx = with(density) { (effectiveTopOffset + 22.dp).toPx() }
    val collapseProgress by remember(listState, collapseDistancePx) {
        derivedStateOf {
            computeLazyHeaderCollapseProgress(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                collapseDistancePx = collapseDistancePx
            )
        }
    }

    // 点“立即备份”但尚未授予默认目录权限时先跳系统授权，用户授权回到页内后自动补执行这次备份。
    var pendingManualBackup by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var recordToDeleteRef by rememberSaveable { mutableStateOf<String?>(null) }
    val recordToDelete = recordToDeleteRef?.let { ref ->
        state.records.firstOrNull { it.storageRef == ref }
    }

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (manager.setCustomTreeUri(uri)) {
            Toast.makeText(context, "已设置自定义备份文件夹", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "无法获取该文件夹的持久写权限", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(permissionState.storageGranted, state.config.customTreeUri) {
        manager.refreshRecords()
    }

    LaunchedEffect(permissionState.storageGranted, pendingManualBackup) {
        if (pendingManualBackup && permissionState.storageGranted) {
            pendingManualBackup = false
            val outcome = manager.runManualBackup()
            showBackupOutcomeToast(context, outcome)
        }
    }

    fun triggerManualBackup() {
        val config = state.config
        if (!config.usesCustomTree && !permissionState.storageGranted) {
            pendingManualBackup = true
            Toast.makeText(
                context,
                "默认目录需要外部存储权限，授权后将自动开始备份；也可改选自定义文件夹",
                Toast.LENGTH_LONG
            ).show()
            permissionActions.requestStorage()
            return
        }
        uiScope.launch {
            val outcome = manager.runManualBackup()
            if (outcome is BackupRunOutcome.PermissionRequired) {
                pendingManualBackup = true
                permissionActions.requestStorage()
            } else {
                showBackupOutcomeToast(context, outcome)
            }
        }
    }

    fun onToggleAutoBackup(enabled: Boolean) {
        manager.setAutoEnabled(enabled)
        if (enabled && !state.config.usesCustomTree && !permissionState.storageGranted) {
            Toast.makeText(
                context,
                "默认目录需要外部存储权限，正在跳转授权；也可改选自定义文件夹免权限",
                Toast.LENGTH_LONG
            ).show()
            permissionActions.requestStorage()
        }
    }

    CollapsingGlassHeaderContainer(
        title = "自动备份",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = horizontalPadding,
        expandedTopOffset = effectiveTopOffset,
        expandedFontSize = if (compact) TextSizes.Headline else TextSizes.Display,
        collapsedFontSize = if (compact) 18.sp else 19.sp,
        topBarHeight = 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        leadingAction = if (showBackButton) {
            {
                GlassHeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                    onClick = onBack
                )
            }
        } else {
            null
        },
        modifier = modifier
            .fillMaxSize()
            .appPageBackground(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = headerTopPadding,
                end = horizontalPadding,
                bottom = bottomClearance + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                BackupOverviewCard(
                    state = state,
                    onAutoToggle = ::onToggleAutoBackup,
                    onManualBackup = ::triggerManualBackup,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                BackupLocationCard(
                    state = state,
                    storageGranted = permissionState.storageGranted,
                    onRequestStorage = permissionActions.requestStorage,
                    onPickFolder = { treePickerLauncher.launch(null) },
                    onUseDefault = manager::useDefaultExternalDirectory,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                BackupScheduleCard(
                    state = state,
                    onSelectFrequency = manager::setScheduleFrequency,
                    onSelectDayOfWeek = manager::setScheduleDayOfWeek,
                    onOpenTimePicker = { showTimePicker = true },
                    onSelectRetention = manager::setRetentionCount,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                BackupComponentsCard(
                    selected = state.config.components,
                    available = state.availableComponents,
                    onToggle = manager::setComponentEnabled,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                BackupHistoryCard(
                    state = state,
                    onRefresh = { uiScope.launch { manager.refreshRecords() } },
                    onDeleteRequest = { recordToDeleteRef = it.storageRef },
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }
        }
    }

    if (showTimePicker) {
        BackupTimePickerDialog(
            initialHour = state.config.scheduleHour,
            initialMinute = state.config.scheduleMinute,
            frequency = state.config.frequency,
            scheduleDayOfWeek = state.config.scheduleDayOfWeek,
            onConfirm = { hour, minute ->
                showTimePicker = false
                manager.setScheduleTime(hour, minute)
            },
            onDismiss = { showTimePicker = false }
        )
    }

    recordToDelete?.let { record ->
        BackupDeleteConfirmDialog(
            record = record,
            onDismiss = { recordToDeleteRef = null },
            onConfirm = {
                val target = record
                recordToDeleteRef = null
                uiScope.launch {
                    val ok = manager.deleteBackup(target)
                    Toast.makeText(
                        context,
                        if (ok) "已删除 ${target.fileName}" else "删除失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
