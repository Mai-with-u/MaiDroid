package org.maiwithu.maidroid.appupdate

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** 一个安装包（或已安装应用）的包名、版本号和签名证书 SHA-256（小写十六进制）。 */
internal data class AppPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>
)

/** 当前安装的 MaiDroid 自己的身份；读不出签名时签名集合为空。 */
internal fun installedPackageIdentity(context: Context): AppPackageIdentity? = runCatching {
    val packageManager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    info.identity()
}.getOrNull()

/** 读下载好的 APK 的身份；不是有效的安装包返回 null。部分系统读不出安装包的签名，这时签名集合为空。 */
internal fun archivePackageIdentity(context: Context, apk: File): AppPackageIdentity? = runCatching {
    val packageManager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    }
    info?.identity()
}.getOrNull()

private fun PackageInfo.identity(): AppPackageIdentity {
    val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        signatures
    }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
    return AppPackageIdentity(
        packageName = packageName,
        versionCode = versionCode,
        // 和发布流程里 apksigner 打印的“certificate SHA-256 digest”是同一个值：DER 证书的 SHA-256
        signerSha256 = signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    )
}

/**
 * 当前安装的应用能不能直接覆盖安装这个 Release：Release 说明里写的发布证书和自己的签名证书一致才行。
 * debug 包用的是每台机器自己的调试密钥，和发布证书对不上，只能去下载页手动处理。
 * Release 没写证书（老版本）或读不出自己的签名时不拦，下载完还会再按安装包本身核对一次。
 */
internal fun canInstallOverCurrent(releaseCertSha256: String?, ownSignerSha256: Set<String>): Boolean =
    releaseCertSha256 == null || ownSignerSha256.isEmpty() || releaseCertSha256.lowercase() in ownSignerSha256

/**
 * 下载完、交给系统安装器之前的最后核对，返回不能安装的原因，没问题返回 null。
 * 这几种情况系统安装器都会拒绝，但只报一句“安装失败”，所以先在这里说清楚原因。
 */
internal fun downloadedPackageProblem(
    downloaded: AppPackageIdentity?,
    installed: AppPackageIdentity
): String? = when {
    downloaded == null -> "下载的文件不是有效的安装包"
    downloaded.packageName != installed.packageName ->
        "下载的安装包不是 MaiDroid（包名 ${downloaded.packageName}）"
    downloaded.versionCode <= installed.versionCode ->
        "下载的安装包版本号（${downloaded.versionCode}）不比当前（${installed.versionCode}）新，系统不允许安装"
    downloaded.signerSha256.isNotEmpty() &&
        installed.signerSha256.isNotEmpty() &&
        downloaded.signerSha256 != installed.signerSha256 ->
        "下载的安装包签名和当前安装的 MaiDroid 不同，系统不允许覆盖安装"
    else -> null
}

/** 流式计算文件的 SHA-256（小写十六进制），不把整个安装包读进内存。 */
internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
