package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotInteractiveShellTest {
    @Test
    fun `interactive shell command line starts with proot, uses target cwd and resets loader path`() {
        val tempRoot = Files.createTempDirectory("maidroid-proot-shell-test").toFile()
        try {
            val paths = RuntimePaths(
                appFilesDir = File(tempRoot, "files"),
                nativeLibraryDir = File(tempRoot, "native")
            )
            val commandLine = ProotCommand(paths, mountExternalStorage = { false })
                .interactiveShellCommandLine("/opt/MaiBot")

            assertEquals(paths.prootExecutable.absolutePath, commandLine.first())
            val workDirFlagIndex = commandLine.indexOf("-w")
            assertTrue(workDirFlagIndex >= 0)
            assertEquals("/opt/MaiBot", commandLine[workDirFlagIndex + 1])

            val shellBody = commandLine.last()
            assertTrue(shellBody.contains("unset LD_LIBRARY_PATH;"))
            assertTrue(shellBody.contains("TERM=xterm-256color"))
            assertTrue(shellBody.contains("exec /bin/bash -i"))
            assertTrue(shellBody.contains("exec /bin/sh -i"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `interactive shell environment merges host vars while enforcing proot overrides`() {
        val tempRoot = Files.createTempDirectory("maidroid-proot-env-test").toFile()
        try {
            val paths = RuntimePaths(
                appFilesDir = File(tempRoot, "files"),
                nativeLibraryDir = File(tempRoot, "native")
            )
            val env = ProotCommand(paths).interactiveShellEnvironment(
                hostEnvironment = mapOf(
                    "ANDROID_DATA" to "/data",
                    "TERM" to "dumb",
                    "PROOT_LOADER" to "/wrong/path"
                )
            )

            assertTrue(env.contains("ANDROID_DATA=/data"))
            assertTrue(env.contains("TERM=xterm-256color"))
            assertTrue(env.contains("COLORTERM=truecolor"))
            assertTrue(env.contains("LANG=C.UTF-8"))
            assertTrue(env.contains("PROOT_LOADER=${paths.prootLoader.absolutePath}"))
            assertFalse(env.contains("TERM=dumb"))
            assertFalse(env.contains("PROOT_LOADER=/wrong/path"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
