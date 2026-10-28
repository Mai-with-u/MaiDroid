import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

private val utf8TextPatterns = listOf(
    "**/*.gradle",
    "**/*.gradle.kts",
    "**/*.java",
    "**/*.kt",
    "**/*.kts",
    "**/*.md",
    "**/*.properties",
    "**/*.toml",
    "**/*.txt",
    "**/*.xml",
    "**/*.keep",
    ".editorconfig",
    ".gitattributes",
    "gradlew",
    "LICENSE"
)

abstract class CheckUtf8EncodingTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val textFiles: ConfigurableFileCollection

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun checkEncoding() {
        val rootDir = projectRoot.get().asFile
        val invalidFiles = textFiles.files.filter { file ->
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

            try {
                decoder.decode(ByteBuffer.wrap(file.readBytes()))
                false
            } catch (_: Exception) {
                true
            }
        }

        check(invalidFiles.isEmpty()) {
            invalidFiles.joinToString(
                separator = "\n",
                prefix = "Non UTF-8 text files found:\n"
            ) { file ->
                file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            }
        }
    }
}

private val checkUtf8Encoding = tasks.register<CheckUtf8EncodingTask>("checkUtf8Encoding") {
    group = "verification"
    description = "Checks project text files are valid UTF-8."

    textFiles.from(fileTree(rootDir) {
        utf8TextPatterns.forEach { include(it) }
        exclude("**/.git/**")
        exclude("**/.gradle/**")
        exclude("**/.idea/**")
        exclude("**/build/**")
    })
    projectRoot.set(layout.projectDirectory)
}

// 版本递增：./gradlew bumpVersion -Pbump=major|minor|patch
// 触发条件：准备发布新版本时，在 dev 分支上手动执行，-Pbump 指定 a.b.c 里要 +1 的是哪一位。
// 执行顺序：校验 versionName 和 CHANGELOG 的 Unreleased 一节 -> 递增指定位并把更低的位清零
// -> versionCode 固定 +1 -> 把 Unreleased 改成新版本一节，并在最上面补一个空的 Unreleased。
// 原因：发布流程读取 CHANGELOG 最上面带版本号的一节作为版本号和说明，versionName 必须与之一致，
// 手工改两处容易漏改或写岔。
abstract class BumpVersionTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val bump: Property<String>

    @get:Internal
    abstract val appBuildFile: RegularFileProperty

    @get:Internal
    abstract val changelogFile: RegularFileProperty

    @TaskAction
    fun bumpVersion() {
        val kind = bump.orNull
            ?: throw GradleException("请用 -Pbump 指定要递增哪一位：major、minor 或 patch，例如 ./gradlew bumpVersion -Pbump=minor")

        val buildFile = appBuildFile.get().asFile
        val buildText = buildFile.readText()
        val nameRegex = Regex("versionName = \"(\\d+)\\.(\\d+)\\.(\\d+)\"")
        val codeRegex = Regex("versionCode = (\\d+)")
        val nameMatch = nameRegex.find(buildText)
            ?: throw GradleException("app/build.gradle.kts 里的 versionName 必须是 a.b.c 的纯数字格式")
        val codeMatch = codeRegex.find(buildText)
            ?: throw GradleException("app/build.gradle.kts 里没有找到 versionCode")

        val (major, minor, patch) = nameMatch.destructured.toList().map { it.toInt() }
        // 高位 +1 时更低的位清零：minor +1 则 patch 归零，major +1 则 minor、patch 都归零
        val (nextMajor, nextMinor, nextPatch) = when (kind) {
            "major" -> Triple(major + 1, 0, 0)
            "minor" -> Triple(major, minor + 1, 0)
            "patch" -> Triple(major, minor, patch + 1)
            else -> throw GradleException("-Pbump 只能是 major、minor 或 patch，收到的是 $kind")
        }
        val oldName = "$major.$minor.$patch"
        val newName = "$nextMajor.$nextMinor.$nextPatch"
        val oldCode = codeMatch.groupValues[1].toInt()
        val newCode = oldCode + 1

        val changelog = changelogFile.get().asFile
        val lines = changelog.readLines()
        val start = lines.indexOfFirst { it.trim().equals("## [Unreleased]", ignoreCase = true) }
        if (start < 0) {
            throw GradleException("CHANGELOG.md 里没有 \"## [Unreleased]\" 一节，请先把本次更新的说明写在它下面")
        }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        if (lines.subList(start + 1, end).all { it.isBlank() }) {
            throw GradleException("CHANGELOG.md 的 Unreleased 一节还没有内容，请先写好本次更新的说明")
        }
        if (lines.any { it.startsWith("## ") && it.contains("[$newName]") }) {
            throw GradleException("CHANGELOG.md 里已经有 $newName 这一节了")
        }

        // 校验都通过后再写文件，避免只改了一半
        val newLines = lines.subList(0, start) +
            listOf("## [Unreleased]", "", "## [$newName] - ${LocalDate.now()}") +
            lines.subList(start + 1, lines.size)
        changelog.writeText(newLines.joinToString("\n") + "\n")
        buildFile.writeText(
            buildText
                .replaceFirst(nameRegex, "versionName = \"$newName\"")
                .replaceFirst(codeRegex, "versionCode = $newCode")
        )
        logger.lifecycle("版本号 $oldName -> $newName，versionCode $oldCode -> $newCode")
    }
}

tasks.register<BumpVersionTask>("bumpVersion") {
    group = "release"
    description = "递增版本号：-Pbump=major|minor|patch，并把 CHANGELOG.md 的 Unreleased 改成新版本一节。"
    bump.set(providers.gradleProperty("bump"))
    appBuildFile.set(layout.projectDirectory.file("app/build.gradle.kts"))
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkUtf8Encoding)
    }
}
