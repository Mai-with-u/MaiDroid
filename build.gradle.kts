import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
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

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkUtf8Encoding)
    }
}
