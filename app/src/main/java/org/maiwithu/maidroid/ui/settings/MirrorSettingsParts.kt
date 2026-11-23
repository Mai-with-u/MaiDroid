package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.MirrorCategory
import org.maiwithu.maidroid.runtime.MirrorManager
import org.maiwithu.maidroid.runtime.MirrorPreference
import org.maiwithu.maidroid.runtime.MirrorProbeResult
import org.maiwithu.maidroid.runtime.MirrorProbeStatus
import org.maiwithu.maidroid.runtime.MirrorSelectionMode
import org.maiwithu.maidroid.runtime.MirrorSettingsState
import org.maiwithu.maidroid.runtime.PyPiMirrors
import org.maiwithu.maidroid.runtime.orderAptMirrors
import org.maiwithu.maidroid.runtime.orderGitHubMirrors
import org.maiwithu.maidroid.runtime.orderPyPiMirrors
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LiquidSegmentItem
import org.maiwithu.maidroid.ui.component.LiquidSegmentedSelector
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogTextField
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

private data class MirrorPresetRowModel(val key: String, val name: String, val addressText: String)

/** 生成设置页“镜像源设置”一行的状态摘要；全自动时强调自动测速择优，有手动项时点明分类与选择。 */
internal fun mirrorSettingsSummaryText(state: MirrorSettingsState): String {
    val manualParts = MirrorCategory.entries.mapNotNull { category ->
        val pref = state.preferences[category] ?: return@mapNotNull null
        when (pref.mode) {
            MirrorSelectionMode.Auto -> null
            MirrorSelectionMode.Preset -> "${category.shortLabel}(${resolvePresetLabel(category, pref.presetValue)})"
            MirrorSelectionMode.Custom -> "${category.shortLabel}(自定义)"
        }
    }
    if (manualParts.isEmpty()) return "自动测速优选（GitHub / Debian / PyPI）"
    val prefix = "手动：${manualParts.joinToString(" · ")}"
    return if (manualParts.size < MirrorCategory.entries.size) "$prefix · 其余自动" else prefix
}

private fun resolvePresetLabel(category: MirrorCategory, key: String): String = when (category) {
    MirrorCategory.GitHub -> GitHubMirrors.ALL.firstOrNull { it.prefix == key }?.name
    MirrorCategory.Apt -> AptMirrors.ALL.firstOrNull { it.baseUrl == key }?.name
    MirrorCategory.PyPi -> PyPiMirrors.ALL.firstOrNull { it.indexUrl == key }?.name
} ?: "预设"

private fun buildPresetRows(category: MirrorCategory): List<MirrorPresetRowModel> = when (category) {
    MirrorCategory.GitHub -> GitHubMirrors.ALL.map {
        MirrorPresetRowModel(it.prefix, it.name, if (it.isDirect) "不经镜像反代，直连 GitHub 官方" else it.prefix)
    }
    MirrorCategory.Apt -> AptMirrors.ALL.map { MirrorPresetRowModel(it.baseUrl, it.name, it.baseUrl) }
    MirrorCategory.PyPi -> PyPiMirrors.ALL.map {
        MirrorPresetRowModel(it.indexUrl, it.name, if (it.isBuiltInDefault) "沿用 MaiBot 项目内置索引配置" else it.indexUrl)
    }
}

private fun fastestReachableSummary(
    category: MirrorCategory,
    probes: Map<String, MirrorProbeResult>,
    isTesting: Boolean
): String {
    val autoPref = MirrorPreference(mode = MirrorSelectionMode.Auto)
    val best = when (category) {
        MirrorCategory.GitHub -> orderGitHubMirrors(autoPref, probes).firstOrNull()?.let { it.name to probes[it.prefix] }
        MirrorCategory.Apt -> orderAptMirrors(autoPref, probes).firstOrNull()?.let { it.name to probes[it.baseUrl] }
        MirrorCategory.PyPi -> orderPyPiMirrors(autoPref, probes).firstOrNull()?.let { it.name to probes[it.indexUrl] }
    }
    val probe = best?.second
    return when {
        probe?.status == MirrorProbeStatus.Reachable && probe.latencyMs != null ->
            "当前最快：${best.first}（${probe.latencyMs.coerceAtLeast(1L)} ms）"
        isTesting -> "正在并发测速各镜像节点的连通性与延迟..."
        else -> "按测速结果优先选用最快可用线路，失败时顺位兜底切换"
    }
}

/** 镜像源设置页与 OOBE 弹窗共用的交互状态控制器，避免分类切换、草稿输入与测速事件处理重复实现。 */
@Stable
internal class MirrorEditorState(
    private val manager: MirrorManager,
    private val scope: CoroutineScope,
    private val stateProvider: () -> MirrorSettingsState,
    private val selectedCategoryState: MutableState<MirrorCategory>,
    private val githubDraftState: MutableState<String>,
    private val aptDraftState: MutableState<String>,
    private val pypiDraftState: MutableState<String>,
    private val validationErrorState: MutableState<String?>,
    private val saveNoticeState: MutableState<String?>
) {
    val state: MirrorSettingsState get() = stateProvider()
    val selectedCategory: MirrorCategory get() = selectedCategoryState.value
    val anyTesting: Boolean get() = state.testing.values.any { it }
    val isCategoryTesting: Boolean get() = state.testing[selectedCategory] == true
    val currentPreference: MirrorPreference get() = state.preferences.getValue(selectedCategory)
    val currentProbes: Map<String, MirrorProbeResult> get() = state.probes.getValue(selectedCategory)
    val currentCustomProbe: MirrorProbeResult? get() = state.customProbes[selectedCategory]
    val validationError: String? get() = validationErrorState.value
    val saveNotice: String? get() = saveNoticeState.value
    val currentDraft: String get() = draftStateFor(selectedCategory).value

    private fun draftStateFor(category: MirrorCategory): MutableState<String> = when (category) {
        MirrorCategory.GitHub -> githubDraftState
        MirrorCategory.Apt -> aptDraftState
        MirrorCategory.PyPi -> pypiDraftState
    }

    fun selectCategory(category: MirrorCategory) {
        selectedCategoryState.value = category
        validationErrorState.value = null
        saveNoticeState.value = null
    }

    fun updateDraft(raw: String) {
        validationErrorState.value = null
        saveNoticeState.value = null
        draftStateFor(selectedCategory).value = raw
    }

    fun selectAuto() {
        validationErrorState.value = null
        saveNoticeState.value = null
        manager.selectAuto(selectedCategory)
    }

    fun selectPreset(key: String) {
        validationErrorState.value = null
        saveNoticeState.value = null
        manager.selectPreset(selectedCategory, key)
    }

    fun testCategory() {
        scope.launch { manager.testCategory(selectedCategory, force = true) }
    }

    fun testAll() {
        scope.launch { manager.testAll() }
    }

    fun testCustom() {
        val category = selectedCategory
        val raw = currentDraft
        scope.launch {
            validationErrorState.value = null
            saveNoticeState.value = null
            val err = manager.testCustomUrl(category, raw)
            if (err != null) validationErrorState.value = err
        }
    }

    fun saveCustom() {
        val category = selectedCategory
        val raw = currentDraft
        val err = manager.saveCustomUrl(category, raw)
        if (err != null) {
            validationErrorState.value = err
            saveNoticeState.value = null
            return
        }
        val normalized = manager.state.value.preferences.getValue(category).customUrl
        draftStateFor(category).value = normalized
        validationErrorState.value = null
        saveNoticeState.value = "已保存并启用自定义${category.shortLabel}镜像"
        if (manager.state.value.customProbes[category]?.key != normalized) {
            scope.launch { manager.testCustomUrl(category, normalized) }
        }
    }
}

/** 创建并记忆 [MirrorEditorState]；首次进入且无历史测速缓存时在后台并发测速一次。 */
@Composable
internal fun rememberMirrorEditorState(
    manager: MirrorManager,
    state: MirrorSettingsState
): MirrorEditorState {
    val latestState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val selectedCategoryState = rememberSaveable { mutableStateOf(MirrorCategory.GitHub) }
    val githubDraftState = rememberSaveable { mutableStateOf(state.preferences.getValue(MirrorCategory.GitHub).customUrl) }
    val aptDraftState = rememberSaveable { mutableStateOf(state.preferences.getValue(MirrorCategory.Apt).customUrl) }
    val pypiDraftState = rememberSaveable { mutableStateOf(state.preferences.getValue(MirrorCategory.PyPi).customUrl) }
    val validationErrorState = remember { mutableStateOf<String?>(null) }
    val saveNoticeState = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manager) {
        if (!manager.hasAnyTestedCache()) manager.testAll()
    }

    return remember(manager, scope) {
        MirrorEditorState(
            manager = manager,
            scope = scope,
            stateProvider = { latestState },
            selectedCategoryState = selectedCategoryState,
            githubDraftState = githubDraftState,
            aptDraftState = aptDraftState,
            pypiDraftState = pypiDraftState,
            validationErrorState = validationErrorState,
            saveNoticeState = saveNoticeState
        )
    }
}

@Composable
internal fun MirrorOverviewCard(
    editor: MirrorEditorState,
    modifier: Modifier = Modifier
) {
    val categoryTesting = editor.isCategoryTesting
    val anyTesting = editor.anyTesting
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "GitHub、Debian 软件源与 PyPI 依赖在自动模式下会并发测速并优先使用最快线路，也可以手动锁定预设或填入自定义镜像地址。",
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 18.sp
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidButton(
                    onClick = editor::testCategory,
                    enabled = !categoryTesting,
                    style = LiquidButtonStyle.SoftAccent,
                    minHeight = 40.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    if (categoryTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp, color = BrandOrange)
                    } else {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = if (categoryTesting) "测速本组中" else "测速${editor.selectedCategory.shortLabel}",
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                LiquidButton(
                    onClick = editor::testAll,
                    enabled = !anyTesting,
                    style = LiquidButtonStyle.Primary,
                    minHeight = 40.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    if (anyTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp, color = TextPrimary)
                    } else {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = if (anyTesting) "全部测速中" else "全部测速",
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun MirrorCategorySegmentBar(
    selectedCategory: MirrorCategory,
    onSelectCategory: (MirrorCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember {
        MirrorCategory.entries.map { category ->
            LiquidSegmentItem(
                label = when (category) {
                    MirrorCategory.GitHub -> "GitHub"
                    MirrorCategory.Apt -> "Debian 源"
                    MirrorCategory.PyPi -> "PyPI"
                }
            )
        }
    }
    LiquidSegmentedSelector(
        items = items,
        selectedIndex = MirrorCategory.entries.indexOf(selectedCategory),
        onSelect = { index -> MirrorCategory.entries.getOrNull(index)?.let(onSelectCategory) },
        height = 44.dp,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun MirrorModeAndPresetsCard(
    editor: MirrorEditorState,
    modifier: Modifier = Modifier
) {
    val category = editor.selectedCategory
    val preference = editor.currentPreference
    val probes = editor.currentProbes
    val presets = remember(category) { buildPresetRows(category) }
    val autoSummary = remember(category, probes, editor.isCategoryTesting) {
        fastestReachableSummary(category, probes, editor.isCategoryTesting)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = category.title, color = TextPrimary, fontSize = TextSizes.Body, fontWeight = FontWeight.SemiBold)
                Text(text = category.subtitle, color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 16.sp)
            }
            HorizontalCardHairline()
            MirrorOptionRow(
                title = "自动测速优选（推荐）",
                subtitle = autoSummary,
                selected = preference.mode == MirrorSelectionMode.Auto,
                probe = null,
                onClick = editor::selectAuto
            )
            presets.forEach { row ->
                HorizontalCardHairline()
                MirrorOptionRow(
                    title = row.name,
                    subtitle = row.addressText,
                    selected = preference.mode == MirrorSelectionMode.Preset && preference.presetValue == row.key,
                    probe = probes[row.key],
                    onClick = { editor.selectPreset(row.key) }
                )
            }
        }
    }
}

@Composable
private fun MirrorOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    probe: MirrorProbeResult?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) CardSurfaceSelected.copy(alpha = 0.72f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) BrandOrange else Color.Transparent)
                .border(width = if (selected) 0.dp else 1.5.dp, color = if (selected) BrandOrange else TextFaint, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(imageVector = Icons.Outlined.Check, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(13.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = if (selected) BrandOrange else TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (probe != null) ProbeStatusBadge(probe = probe)
    }
}

@Composable
private fun ProbeStatusBadge(probe: MirrorProbeResult) {
    when (probe.status) {
        MirrorProbeStatus.Testing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.6.dp, color = BrandOrange)
            Text(text = "测速中", color = BrandOrange, fontSize = TextSizes.Caption, fontWeight = FontWeight.Medium)
        }
        MirrorProbeStatus.Reachable -> {
            val ms = (probe.latencyMs ?: 1L).coerceAtLeast(1L)
            val color = when {
                ms < 350L -> StatusOnline
                ms < 1000L -> StatusWarning
                else -> BrandOrange
            }
            ProbeBadgePill(text = "$ms ms", color = color)
        }
        MirrorProbeStatus.Failed -> ProbeBadgePill(
            text = probe.errorReason?.takeIf { it.isNotBlank() } ?: "不可用",
            color = StatusDanger
        )
        MirrorProbeStatus.Idle -> Text(text = "未测速", color = TextFaint, fontSize = TextSizes.Caption)
    }
}

@Composable
private fun ProbeBadgePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun CustomMirrorInputCard(
    editor: MirrorEditorState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        CustomMirrorInnerContent(editor = editor, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun CustomMirrorDialogSection(editor: MirrorEditorState) {
    val testing = editor.isCategoryTesting
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CustomMirrorInnerContent(editor = editor)
        }
        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "只测速当前分类（${editor.selectedCategory.shortLabel}）",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (testing) "测速中..." else "测速本组",
                    color = if (testing) TextFaint else BrandOrange,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !testing, onClick = editor::testCategory)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                text = "保存或测速后立即写入容器配置，装机、拉取与更新自动生效。",
                color = TextFaint,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun CustomMirrorInnerContent(
    editor: MirrorEditorState,
    modifier: Modifier = Modifier
) {
    val category = editor.selectedCategory
    val preference = editor.currentPreference
    val customProbe = editor.currentCustomProbe
    val validationError = editor.validationError
    val saveNotice = editor.saveNotice
    val isCustomActive = preference.mode == MirrorSelectionMode.Custom && preference.customUrl.isNotBlank()
    val customTesting = customProbe?.status == MirrorProbeStatus.Testing

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.inputLabel,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isCustomActive) ProbeBadgePill(text = "当前已启用", color = BrandOrange)
        }
        MaiDialogTextField(
            value = editor.currentDraft,
            onValueChange = editor::updateDraft,
            label = category.shortLabel,
            placeholder = category.inputPlaceholder,
            errorText = validationError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        Text(text = category.inputHint, color = TextFaint, fontSize = TextSizes.Caption, lineHeight = 16.sp)
        if (customProbe != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "最近测速：", color = TextMuted, fontSize = TextSizes.Caption)
                ProbeStatusBadge(probe = customProbe)
            }
        }
        if (saveNotice != null && validationError == null) {
            Text(text = saveNotice, color = StatusOnline, fontSize = TextSizes.Caption, fontWeight = FontWeight.Medium)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidButton(
                onClick = editor::testCustom,
                enabled = !customTesting,
                style = LiquidButtonStyle.Secondary,
                minHeight = 40.dp,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (customTesting) "测试中..." else "测试该地址",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            LiquidButton(
                onClick = editor::saveCustom,
                style = LiquidButtonStyle.Primary,
                minHeight = 40.dp,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "保存并启用",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HorizontalCardHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(CardDivider)
    )
}
