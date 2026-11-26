package org.maiwithu.maidroid.backup

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies

/**
 * 定时自动备份的持久化系统任务兜底入口：
 * 当应用未在前台挂起常驻服务（例如用户执行了安全退出或系统刚开机），由 JobScheduler 唤醒补齐当天的备份。
 */
class AutoBackupJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeTask: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeTask?.cancel()
        activeTask = jobScope.launch {
            try {
                appDependencies.backupManager.runScheduledBackupIfDue()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeTask?.cancel()
        activeTask = null
        return true
    }

    override fun onDestroy() {
        jobScope.cancel()
        super.onDestroy()
    }
}
