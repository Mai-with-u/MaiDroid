package org.maiwithu.maidroid.platform.napcat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** 某一时刻从文件和端口读到的 NapCat 状况。 */
internal data class NapCatStatusSnapshot(
    val installed: Boolean,
    val qrCodePath: String?,
    val qrCodeLastModified: Long,
    val oneBotReachable: Boolean,
    val oneBotConfig: NapCatOneBotConfig?,
    val accountId: String?
)

/** NapCat 安装脚本写在进度文件里的进度（0~1）和当前步骤说明。 */
internal data class NapCatInstallProgress(
    val value: Float,
    val description: String
)

/**
 * 从容器里的文件和 OneBot 端口读出 NapCat 当前状况，不调用 NapCat 的接口。
 * 全部是阻塞 IO（包括最多 250 毫秒的端口探测），调用方要放在 IO 线程。
 */
internal class NapCatStatusReader(private val files: NapCatFiles) {
    private companion object {
        val AccountConfigName = Regex("""onebot11_(\d+)\.json""")
        val LoginDataDirName = Regex("""\.(\d+)""")
        const val ONE_BOT_CONNECT_TIMEOUT_MS = 250
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * preferredUin 是向 NapCat 问到的当前登录 QQ 号：知道时读它自己的 onebot11_<QQ号>.json；
     * checkConnection 为 false 时跳过端口探测（应用刚启动、还在主线程时用）。
     */
    fun read(checkConnection: Boolean = true, preferredUin: String? = null): NapCatStatusSnapshot {
        val oneBotConfig = readOneBotConfig(preferredUin)
        val qrCodeFile = files.qrCodeFile.takeIf { it.isFile }
        return NapCatStatusSnapshot(
            installed = files.isInstalled(),
            qrCodePath = qrCodeFile?.absolutePath,
            qrCodeLastModified = qrCodeFile?.lastModified() ?: 0L,
            oneBotReachable = checkConnection && oneBotConfig?.let(::isOneBotReachable) == true,
            oneBotConfig = oneBotConfig,
            accountId = readAccountId(oneBotConfig)
        )
    }

    /** 安装开始前删掉上一次留下的进度文件，避免进度环一上来就显示旧进度。 */
    fun clearInstallProgress() {
        runCatching { files.installProgressFile.parentFile?.mkdirs() }
        runCatching { files.installProgressFile.delete() }
        runCatching { files.installProgressDescriptionFile.delete() }
    }

    /**
     * 读安装脚本汇报的进度。两个文件都还没写出来时返回 null；
     * 进度写成百分数（>1）时换算成 0~1，读不出数字时沿用 fallbackProgress。
     */
    fun readInstallProgress(fallbackProgress: Float): NapCatInstallProgress? {
        val progressFile = files.installProgressFile
        val descriptionFile = files.installProgressDescriptionFile
        if (!progressFile.isFile && !descriptionFile.isFile) return null

        val rawProgress = progressFile.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toFloatOrNull()
        val normalizedProgress = rawProgress
            ?.let { if (it > 1f) it / 100f else it }
            ?.coerceIn(0f, 1f)
            ?: fallbackProgress
        val description = descriptionFile.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()
        return NapCatInstallProgress(value = normalizedProgress, description = description)
    }

    // 取第一个启用的 WebSocket 服务端（都没启用就取第一个）；文件不存在或解析失败返回 null。
    private fun readOneBotConfig(preferredUin: String?): NapCatOneBotConfig? {
        val file = findOneBotConfigFile(preferredUin) ?: return null

        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val network = root["network"]?.jsonObject
            val server = network
                ?.get("websocketServers")
                ?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.let { servers ->
                    servers.firstOrNull {
                        it["enable"]?.jsonPrimitive?.booleanOrNull == true
                    } ?: servers.firstOrNull()
                }
                ?: return null

            NapCatOneBotConfig(
                serviceType = "WebSocket Server",
                name = server["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "WsServer" },
                enabled = server["enable"]?.jsonPrimitive?.booleanOrNull ?: false,
                host = server["host"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "127.0.0.1" },
                port = server["port"]?.jsonPrimitive?.intOrNull,
                token = server["token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                messagePostFormat = server["messagePostFormat"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reportSelfMessage = server["reportSelfMessage"]?.jsonPrimitive?.booleanOrNull,
                enableForcePushEvent = server["enableForcePushEvent"]?.jsonPrimitive?.booleanOrNull,
                debug = server["debug"]?.jsonPrimitive?.booleanOrNull,
                heartInterval = server["heartInterval"]?.jsonPrimitive?.intOrNull,
                configPath = "/root/napcat/config/${file.name}"
            )
        }.getOrNull()
    }

    // 知道当前登录账号时用它自己的配置；不知道（未登录或问不到 NapCat）时退回最近修改的账号配置，
    // 一个账号配置都没有时用安装脚本写的默认 onebot11.json。
    private fun findOneBotConfigFile(preferredUin: String?): File? {
        preferredUin
            ?.let { files.configDir.resolve("onebot11_$it.json") }
            ?.takeIf { it.isFile }
            ?.let { return it }
        val accountConfig = files.configDir.listFiles()
            ?.filter { it.isFile && AccountConfigName.matches(it.name) }
            ?.maxByOrNull { it.lastModified() }
        return accountConfig ?: files.defaultOneBotConfig.takeIf { it.isFile }
    }

    // QQ 号优先取自账号配置文件名，取不到再看 QQ 本地登录数据目录的子目录名。
    private fun readAccountId(oneBotConfig: NapCatOneBotConfig?): String? {
        val fromConfig = oneBotConfig
            ?.configPath
            ?.let { AccountConfigName.find(it)?.groupValues?.getOrNull(1) }
        if (!fromConfig.isNullOrBlank()) return fromConfig

        return files.qqLoginDataDir.listFiles()
            ?.asSequence()
            ?.mapNotNull { LoginDataDirName.matchEntire(it.name)?.groupValues?.getOrNull(1) }
            ?.firstOrNull()
    }

    // OneBot 服务端只有 QQ 登录后才会监听，所以“端口能连上”等价于“QQ 在线且 MaiBot 能连”。
    private fun isOneBotReachable(config: NapCatOneBotConfig): Boolean {
        val port = config.port ?: return false
        if (!config.enabled) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.host, port), ONE_BOT_CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }
}
