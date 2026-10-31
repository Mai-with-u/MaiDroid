package org.maiwithu.maidroid.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import org.maiwithu.maidroid.appDependencies

/**
 * 用系统“文件”应用（DocumentsUI）打开 ContainerDocumentsProvider 的 MaiBot 根，设置页“MaiBot 文件”一行调用。
 *
 * 通知根列表变化 -> 按根打开（文件应用侧边栏会选中 MaiBot）-> 个别系统只认目录，再按目录打开；
 * 都没有应用接手时返回 false，由界面提示用户去 MT 管理器等文件管理器里添加。
 * 先通知是因为：OOBE 完成前打开过文件应用的话，它缓存的根列表里还没有 MaiBot，按根打开会落到别的位置。
 * 有些厂商的文件管理器（比如荣耀的）不处理这两种请求，但系统里的 DocumentsUI 通常还在，只是没有桌面图标。
 */
internal fun openMaiBotInFileManager(context: Context): Boolean {
    val authority = ContainerDocumentsProvider.AUTHORITY
    context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
    val maiBotDocumentId = ContainerDocumentsProvider.maiBotDocumentId(context.appDependencies.runtimePaths)
    val intents = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(
            DocumentsContract.buildRootUri(authority, ContainerDocumentsProvider.ROOT_MAIBOT),
            Root.MIME_TYPE_ITEM
        ),
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(DocumentsContract.buildDocumentUri(authority, maiBotDocumentId), Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // 换下一种方式
        }
    }
    return false
}
