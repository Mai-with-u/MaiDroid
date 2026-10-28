package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File
import java.security.MessageDigest

/**
 * MaiBot 首次启动会要求在终端里确认 EULA 和隐私协议，没有交互终端时会一直卡住。
 * 它也接受用环境变量预先确认：EULA_AGREE / PRIVACY_AGREE 等于对应协议文件的 MD5。
 * 用户已经在 MaiDroid 的授权弹窗里同意过协议，所以启动 MaiBot 时由这里算好 hash 注入。
 */
internal object MaiBotAgreement {

    /** 两份协议文件都在时返回要注入的环境变量；任一文件缺失（源码还没克隆）返回空表。 */
    fun environment(paths: RuntimePaths): Map<String, String> {
        val eulaHash = md5(paths.maiBotEula) ?: return emptyMap()
        val privacyHash = md5(paths.maiBotPrivacy) ?: return emptyMap()
        return mapOf(
            "EULA_AGREE" to eulaHash,
            "PRIVACY_AGREE" to privacyHash
        )
    }

    /** 协议文件齐全，能算出 hash。OOBE 的“协议确认”一步以此为完成条件。 */
    fun isReady(paths: RuntimePaths): Boolean = environment(paths).isNotEmpty()

    private fun md5(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
