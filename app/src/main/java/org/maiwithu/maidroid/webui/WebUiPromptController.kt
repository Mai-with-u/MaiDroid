package org.maiwithu.maidroid.webui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maiwithu.maidroid.resolveActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Choice
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * WebUI 网页弹窗调度器：
 * 统一实现 GeckoSession 的 [PromptDelegate]，将 `<select>` / alert / confirm / prompt / color
 * 发往 Compose 液态玻璃弹窗宿主，并将日期/时间选择器委托给系统原生 Dialog；
 * 网页主动撤销或宿主释放时统一收尾，防止二次结算或窗口残留。
 */
internal class WebUiPromptController(
    private val hostContext: Context,
    private val logTag: String,
    private val fileChooser: WebUiFileChooser,
    private val isReleased: () -> Boolean,
    private val runOnMain: (() -> Unit) -> Unit
) {
    private val promptIdCounter = AtomicLong(1L)
    private val _activePrompt = MutableStateFlow<WebUiPromptRequest?>(null)

    /** 当前待用户在 Compose 液态玻璃卡片中处理的原生网页弹窗请求，未展示或已结算时为 null。 */
    val activePrompt: StateFlow<WebUiPromptRequest?> = _activePrompt.asStateFlow()

    private var activeDialog: Dialog? = null

    /** 创建挂载到 [GeckoSession.setPromptDelegate] 的完整弹窗委托。 */
    fun createPromptDelegate(): PromptDelegate = object : PromptDelegate {
        override fun onFilePrompt(
            targetSession: GeckoSession,
            prompt: FilePrompt
        ): GeckoResult<PromptResponse> {
            if (isReleased()) {
                return if (prompt.isComplete) GeckoResult() else GeckoResult.fromValue(prompt.dismiss())
            }
            return fileChooser.show(prompt, hostContext)
        }

        override fun onAlertPrompt(
            targetSession: GeckoSession,
            prompt: AlertPrompt
        ): GeckoResult<PromptResponse> = showAlertPrompt(prompt)

        override fun onButtonPrompt(
            targetSession: GeckoSession,
            prompt: ButtonPrompt
        ): GeckoResult<PromptResponse> = showButtonPrompt(prompt)

        override fun onTextPrompt(
            targetSession: GeckoSession,
            prompt: TextPrompt
        ): GeckoResult<PromptResponse> = showTextPrompt(prompt)

        override fun onChoicePrompt(
            targetSession: GeckoSession,
            prompt: ChoicePrompt
        ): GeckoResult<PromptResponse> = showChoicePrompt(prompt)

        override fun onDateTimePrompt(
            targetSession: GeckoSession,
            prompt: DateTimePrompt
        ): GeckoResult<PromptResponse> = showDateTimePrompt(prompt)

        override fun onColorPrompt(
            targetSession: GeckoSession,
            prompt: ColorPrompt
        ): GeckoResult<PromptResponse> = showColorPrompt(prompt)
    }

    /** 返回仍存活且未销毁的宿主 Activity，用于挂载系统原生弹窗；宿主已释放时返回 null。 */
    private fun usableActivity(): Activity? {
        if (isReleased()) return null
        val activity = resolveActivity(hostContext) ?: return null
        return activity.takeUnless { it.isFinishing || it.isDestroyed }
    }

    /** 会话释放时统一收尾当前 Compose 弹窗并取消系统原生对话框。 */
    fun release() {
        _activePrompt.value?.onDismiss?.invoke()
        _activePrompt.value = null
        activeDialog?.cancel()
        activeDialog = null
    }

    private fun showAlertPrompt(prompt: AlertPrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Alert(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showButtonPrompt(prompt: ButtonPrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Confirm(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                onConfirm = { settle { prompt.confirm(ButtonPrompt.Type.POSITIVE) } },
                onCancel = { settle { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showTextPrompt(prompt: TextPrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.TextInput(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                defaultValue = prompt.defaultValue.orEmpty(),
                placeholder = "",
                allowClear = false,
                onConfirm = { value -> settle { prompt.confirm(value) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showChoicePrompt(prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val rows = flattenChoiceItems(toChoiceNodes(prompt.choices))
        if (rows.none { it is WebUiChoiceRow.Option }) {
            return if (prompt.isComplete) GeckoResult() else GeckoResult.fromValue(prompt.dismiss())
        }
        val mode = when (prompt.type) {
            ChoicePrompt.Type.MULTIPLE -> WebUiChoiceMode.Multiple
            ChoicePrompt.Type.MENU -> WebUiChoiceMode.Menu
            else -> WebUiChoiceMode.Single
        }
        val initialSelectedIds = initialSelectedChoiceIds(rows)
        return showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Choice(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                mode = mode,
                rows = rows,
                initialSelectedIds = initialSelectedIds,
                // 单选/菜单模式必须显式调用单字符串重载，避免 GeckoView 对非多选传数组抛出异常。
                onConfirmSingle = { optionId -> settle { prompt.confirm(optionId) } },
                onConfirmMultiple = { selectedIds -> settle { prompt.confirm(selectedIds) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }
    }

    private fun showDateTimePrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        if (usableActivity() == null &&
            prompt.type != DateTimePrompt.Type.MONTH &&
            prompt.type != DateTimePrompt.Type.WEEK
        ) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        return when (prompt.type) {
            DateTimePrompt.Type.DATE -> showDatePickerPrompt(prompt)
            DateTimePrompt.Type.TIME -> showTimePickerPrompt(prompt)
            DateTimePrompt.Type.DATETIME_LOCAL -> showDateTimeLocalPrompt(prompt)
            DateTimePrompt.Type.MONTH,
            DateTimePrompt.Type.WEEK -> showTextDateTimePrompt(prompt)
            else -> showTextDateTimePrompt(prompt)
        }
    }

    private fun showTextDateTimePrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            val fallbackTitle = when (prompt.type) {
                DateTimePrompt.Type.MONTH -> "选择月份"
                DateTimePrompt.Type.WEEK -> "选择星期"
                else -> "输入日期时间"
            }
            val placeholder = when (prompt.type) {
                DateTimePrompt.Type.MONTH -> "格式：YYYY-MM（例如 2026-11）"
                DateTimePrompt.Type.WEEK -> "格式：YYYY-Www（例如 2026-W48）"
                else -> "输入日期或时间"
            }
            WebUiPromptRequest.TextInput(
                id = requestId,
                title = prompt.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                message = "",
                defaultValue = prompt.defaultValue.orEmpty(),
                placeholder = placeholder,
                allowClear = true,
                onConfirm = { value -> settle { prompt.confirm(value.trim()) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showColorPrompt(prompt: ColorPrompt): GeckoResult<PromptResponse> {
        val initialColor = normalizeHexColor(prompt.defaultValue) ?: "#000000"
        val paletteColors = buildList {
            prompt.predefinedValues.orEmpty().forEach { candidate ->
                normalizeHexColor(candidate)?.let(::add)
            }
            addAll(DEFAULT_WEB_UI_COLOR_PALETTE)
        }.distinct()
        return showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.ColorPicker(
                id = requestId,
                title = prompt.title?.takeIf { it.isNotBlank() } ?: "选择颜色",
                initialHex = initialColor,
                paletteHexes = paletteColors,
                onConfirm = { chosen ->
                    val normalized = normalizeHexColor(chosen) ?: initialColor
                    settle { prompt.confirm(normalized) }
                },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }
    }

    private fun showComposePrompt(
        prompt: BasePrompt,
        buildRequest: (Long, (() -> PromptResponse) -> Unit) -> WebUiPromptRequest
    ): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        if (isReleased()) return GeckoResult.fromValue(prompt.dismiss())

        // 切出新 Compose 弹窗前先按取消收尾旧弹窗，防止网页连弹时出现二次结算异常。
        _activePrompt.value?.onDismiss?.invoke()
        _activePrompt.value = null
        activeDialog?.dismiss()

        val requestId = promptIdCounter.getAndIncrement()
        val result = GeckoResult<PromptResponse>()
        val guard = PromptSettlementGuard<PromptResponse>(
            isAlreadyComplete = { prompt.isComplete }
        ) { response ->
            if (_activePrompt.value?.id == requestId) {
                _activePrompt.value = null
            }
            result.complete(response)
        }
        val settle: (() -> PromptResponse) -> Unit = { produce -> guard.settleOnce(produce) }

        // 网页主动撤销弹窗或关闭标签时，同步收起 Compose 弹窗并完成取消结算。
        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(targetPrompt: BasePrompt) {
                runOnMain { guard.settleOnce { targetPrompt.dismiss() } }
            }
        }
        _activePrompt.value = buildRequest(requestId, settle)
        return result
    }

    private fun toChoiceNodes(choices: Array<Choice>?): List<WebUiChoiceNode> =
        choices.orEmpty().map { choice ->
            WebUiChoiceNode(
                id = choice.id.orEmpty(),
                label = choice.label.orEmpty(),
                disabled = choice.disabled,
                selected = choice.selected,
                separator = choice.separator,
                items = toChoiceNodes(choice.items)
            )
        }

    private fun showDatePickerPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        val initial = parseDateValue(prompt.defaultValue) ?: currentDateValue()
        return showStandalonePromptDialog(prompt, "date picker prompt") { activity, complete ->
            createDatePickerDialog(
                activity = activity,
                prompt = prompt,
                initial = initial,
                complete = complete
            ) { picked ->
                complete { prompt.confirm(formatDateValue(picked.year, picked.month, picked.day)) }
            }
        }
    }

    private fun showTimePickerPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        val initial = parseTimeValue(prompt.defaultValue) ?: currentTimeValue()
        return showStandalonePromptDialog(prompt, "time picker prompt") { activity, complete ->
            createTimePickerDialog(
                activity = activity,
                prompt = prompt,
                initial = initial,
                complete = complete
            ) { picked ->
                complete { prompt.confirm(formatTimeValue(picked.hour, picked.minute)) }
            }
        }
    }

    private fun showDateTimeLocalPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        val activity = usableActivity() ?: return GeckoResult.fromValue(prompt.dismiss())
        val initial = parseDateTimeLocalValue(prompt.defaultValue)
            ?: WebUiDateTimeValue(currentDateValue(), currentTimeValue())
        val result = GeckoResult<PromptResponse>()
        var currentDialog: Dialog? = null
        var movingToTimePicker = false
        val guard = PromptSettlementGuard<PromptResponse>(
            isAlreadyComplete = { prompt.isComplete }
        ) { response ->
            if (activeDialog === currentDialog) {
                activeDialog = null
            }
            result.complete(response)
        }
        val complete: (() -> PromptResponse) -> Unit = { produce -> guard.settleOnce(produce) }

        val dateDialog = createDatePickerDialog(
            activity = activity,
            prompt = prompt,
            initial = initial.date,
            complete = complete
        ) { pickedDate ->
            if (movingToTimePicker || prompt.isComplete) return@createDatePickerDialog
            val stepActivity = usableActivity() ?: return@createDatePickerDialog
            // 切换到第二步时间选择器期间阻止第一步对话框关闭监听把本次选择误判为取消。
            movingToTimePicker = true
            val timeDialog = createTimePickerDialog(
                activity = stepActivity,
                prompt = prompt,
                initial = initial.time,
                complete = complete
            ) { pickedTime ->
                complete { prompt.confirm(formatDateTimeLocalValue(pickedDate, pickedTime)) }
            }
            timeDialog.setOnDismissListener {
                if (activeDialog === timeDialog) {
                    activeDialog = null
                }
                guard.settleOnce { prompt.dismiss() }
            }
            currentDialog = timeDialog
            attachActiveDialog(
                prompt = prompt,
                dialog = timeDialog,
                dismissTarget = { currentDialog },
                logLabel = "datetime-local time picker"
            ) {
                guard.settleOnce { prompt.dismiss() }
            }
        }
        dateDialog.setOnDismissListener {
            if (activeDialog === dateDialog) {
                activeDialog = null
            }
            if (!movingToTimePicker) {
                guard.settleOnce { prompt.dismiss() }
            }
        }
        currentDialog = dateDialog
        attachActiveDialog(
            prompt = prompt,
            dialog = dateDialog,
            dismissTarget = { currentDialog },
            logLabel = "datetime-local date picker"
        ) {
            guard.settleOnce { prompt.dismiss() }
        }
        return result
    }

    private fun showStandalonePromptDialog(
        prompt: BasePrompt,
        logLabel: String,
        createDialog: (Activity, (() -> PromptResponse) -> Unit) -> Dialog
    ): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        val activity = usableActivity() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        var dialog: Dialog? = null
        val guard = PromptSettlementGuard<PromptResponse>(
            isAlreadyComplete = { prompt.isComplete }
        ) { response ->
            if (activeDialog === dialog) {
                activeDialog = null
            }
            result.complete(response)
        }
        val created = createDialog(activity) { produce -> guard.settleOnce(produce) }
        dialog = created
        created.setOnDismissListener {
            if (activeDialog === created) {
                activeDialog = null
            }
            guard.settleOnce { prompt.dismiss() }
        }
        attachActiveDialog(
            prompt = prompt,
            dialog = created,
            logLabel = logLabel
        ) {
            guard.settleOnce { prompt.dismiss() }
        }
        return result
    }

    private fun attachActiveDialog(
        prompt: BasePrompt,
        dialog: Dialog,
        dismissTarget: () -> Dialog? = { dialog },
        logLabel: String,
        onShowFailed: () -> Unit
    ) {
        _activePrompt.value?.onDismiss?.invoke()
        _activePrompt.value = null
        // 网页主动撤销或关闭标签页时同步关掉当前原生弹窗，防止界面残留。
        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(targetPrompt: BasePrompt) {
                runOnMain { dismissTarget()?.dismiss() }
            }
        }
        if (activeDialog !== dialog) {
            activeDialog?.dismiss()
        }
        activeDialog = dialog
        runCatching { dialog.show() }.onFailure { error ->
            Log.w(logTag, "Failed to show $logLabel: ${error.message}")
            if (activeDialog === dialog) {
                activeDialog = null
            }
            onShowFailed()
        }
    }

    private fun createDatePickerDialog(
        activity: Activity,
        prompt: DateTimePrompt,
        initial: WebUiDateValue,
        complete: (() -> PromptResponse) -> Unit,
        onPicked: (WebUiDateValue) -> Unit
    ): DatePickerDialog =
        DatePickerDialog(
            activity,
            { _, year, monthZeroBased, dayOfMonth ->
                onPicked(WebUiDateValue(year = year, month = monthZeroBased + 1, day = dayOfMonth))
            },
            initial.year,
            initial.month - 1,
            initial.day
        ).apply {
            prompt.title?.takeIf { it.isNotBlank() }?.let(::setTitle)
            applyDateBounds(this, prompt.minValue, prompt.maxValue)
            setButton(DialogInterface.BUTTON_NEUTRAL, "清除") { _, _ ->
                complete { prompt.confirm("") }
            }
        }

    private fun createTimePickerDialog(
        activity: Activity,
        prompt: DateTimePrompt,
        initial: WebUiTimeValue,
        complete: (() -> PromptResponse) -> Unit,
        onPicked: (WebUiTimeValue) -> Unit
    ): TimePickerDialog =
        TimePickerDialog(
            activity,
            { _, hourOfDay, minute ->
                onPicked(WebUiTimeValue(hour = hourOfDay, minute = minute))
            },
            initial.hour,
            initial.minute,
            true
        ).apply {
            prompt.title?.takeIf { it.isNotBlank() }?.let(::setTitle)
            setButton(DialogInterface.BUTTON_NEUTRAL, "清除") { _, _ ->
                complete { prompt.confirm("") }
            }
        }

    private fun applyDateBounds(picker: DatePickerDialog, minValue: String?, maxValue: String?) {
        val minMillis = parseDateEpochMillis(minValue)
        val maxMillis = parseDateEpochMillis(maxValue)
        if (minMillis != null && maxMillis != null && minMillis > maxMillis) return
        runCatching {
            minMillis?.let { picker.datePicker.minDate = it }
            maxMillis?.let { picker.datePicker.maxDate = it }
        }.onFailure { error ->
            Log.w(logTag, "Ignored invalid date bounds: ${error.message}")
        }
    }

    private fun currentDateValue(): WebUiDateValue =
        Calendar.getInstance().let { cal ->
            WebUiDateValue(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                day = cal.get(Calendar.DAY_OF_MONTH)
            )
        }

    private fun currentTimeValue(): WebUiTimeValue =
        Calendar.getInstance().let { cal ->
            WebUiTimeValue(
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE)
            )
        }
}

/**
 * 弹窗一次性结算保护器：将按钮结果生成推迟到真正尚未结算时才求值，
 * 防止点击确认/取消后系统继续派发 Dialog.onDismiss 时再次调用 GeckoView 的 prompt.dismiss() 引发二次结算崩溃。
 */
internal class PromptSettlementGuard<R>(
    private val isAlreadyComplete: () -> Boolean = { false },
    private val onComplete: (R) -> Unit
) {
    private var settled = false

    fun settleOnce(produceResponse: () -> R): Boolean {
        if (settled || isAlreadyComplete()) {
            settled = true
            return false
        }
        settled = true
        onComplete(produceResponse())
        return true
    }
}
