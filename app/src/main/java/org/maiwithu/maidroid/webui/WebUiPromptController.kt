package org.maiwithu.maidroid.webui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import org.maiwithu.maidroid.resolveActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar

/**
 * WebUI 网页弹窗调度器：
 * 负责托管 GeckoSession 派发的系统原生日期/时间选择器对话框（后续同步承接全部 Compose 网页弹窗），
 * 并在网页撤销或宿主释放时统一收尾，防止二次结算或窗口残留。
 */
internal class WebUiPromptController(
    private val hostContext: Context,
    private val logTag: String,
    private val isReleased: () -> Boolean,
    private val runOnMain: (() -> Unit) -> Unit,
    private val dismissActiveComposePrompt: () -> Unit
) {
    private var activeDialog: Dialog? = null

    /** 返回仍存活且未销毁的宿主 Activity，用于挂载系统原生弹窗；宿主已释放时返回 null。 */
    fun usableActivity(): Activity? {
        if (isReleased()) return null
        val activity = resolveActivity(hostContext) ?: return null
        return activity.takeUnless { it.isFinishing || it.isDestroyed }
    }

    /** 切换至 Compose 弹窗前先关闭可能仍在显示的原生系统对话框。 */
    fun dismissActiveDialog() {
        activeDialog?.dismiss()
    }

    /** 会话释放时强制取消并清空当前系统对话框引用。 */
    fun release() {
        activeDialog?.cancel()
        activeDialog = null
    }

    fun showDatePickerPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
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

    fun showTimePickerPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
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

    fun showDateTimeLocalPrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
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
        dismissActiveComposePrompt()
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
