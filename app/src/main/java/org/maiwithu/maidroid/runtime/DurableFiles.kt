package org.maiwithu.maidroid.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

// sync 正常几秒内结束；存储坏了卡住时也不要一直等下去
private const val SYNC_TIMEOUT_SECONDS = 60L

/**
 * 整个替换一个文本文件，写到一半被杀、存储满了写不完、刚写完就断电都不会留下半截或空文件：
 * 先写同目录的临时文件并刷到存储上（fsync）-> 再改名盖过旧文件。读它的程序（MaiBot 的配置热重载）
 * 也不会读到写了一半的内容；MaiBot 只在 .py 和 _manifest.json 变化时重载插件，临时文件不会触发重载。
 * 失败时抛异常：旧文件原样留着，临时文件删掉。阻塞调用，放在 IO 线程。
 */
internal fun File.writeTextAtomically(text: String) {
    val parent = absoluteFile.parentFile ?: error("$path 没有上级目录")
    parent.mkdirs()
    val staging = File(parent, "$name.maidroid.tmp")
    try {
        FileOutputStream(staging).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        // rename(2)：同一目录内改名是原子的，读的一方要么看到旧文件、要么看到完整的新文件
        Files.move(staging.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        staging.delete()
        throw e
    }
}

/**
 * 把手机上还没写到存储里的数据都刷下去（执行系统自带的 sync，即 sync(2)），返回是否刷完。
 *
 * 解包容器、apt 装软件、克隆源码、uv 装依赖这些步骤一次写下几百 MB，Linux 要过几十秒才真正落盘；
 * 刚报完成就断电或强制关机，重启后文件都在、内容却是空的，标记文件和就绪检查又都说“装好了”，之后怎么点重试都过不去。
 * 所以这些步骤成功后、写完成标记之前都调一次。没有要写的数据时几毫秒就返回。阻塞调用，放在 IO 线程。
 */
internal fun flushFileSystems(): Boolean = runCatching {
    val process = ProcessBuilder("/system/bin/sync").redirectErrorStream(true).start()
    process.inputStream.close()
    process.waitFor(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
}.getOrDefault(false)
