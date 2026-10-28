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

    /**
     * 启动 MaiBot 时要注入的环境变量，每份在的协议文件给一项。
     * 文件不在就不注入那一项，也不拦着安装：上游不再要求确认时照常启动；仍要求时 MaiBot 自己会报错退出，
     * 由等 WebUI 的一方按“反复退出”报出来。
     */
    fun environment(paths: RuntimePaths): Map<String, String> = buildMap {
        hashOf(paths.maiBotEula)?.let { put("EULA_AGREE", it) }
        hashOf(paths.maiBotPrivacy)?.let { put("PRIVACY_AGREE", it) }
    }

    /** 两份协议文件是否都在；只用于在终端里提示，不作为安装的前提。 */
    fun filesPresent(paths: RuntimePaths): Boolean = paths.maiBotEula.isFile && paths.maiBotPrivacy.isFile

    private fun hashOf(file: File): String? =
        if (file.isFile) agreementHash(file.readText(Charsets.UTF_8)) else null
}

/**
 * 按 MaiBot 的算法算协议 hash：它用 Python 文本模式读文件，\r\n 和单独的 \r 都会变成 \n，再按 UTF-8 编码算 MD5。
 * 以前直接对原始字节算，上游哪天提交了 CRLF 换行的协议文件，hash 就对不上，MaiBot 会停在终端里等人输入“同意”。
 */
internal fun agreementHash(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val digest = MessageDigest.getInstance("MD5").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
