package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.ssh.ContainerSshScripts

class AptMirrorsTest {
    @Test
    fun `oobe writes the default mirror and install falls back through every mirror`() {
        assertEquals("http://mirrors.tuna.tsinghua.edu.cn", AptMirrors.DEFAULT.baseUrl)
        // rootfs 刚解包时还没有 CA 证书，源必须是 http
        assertTrue(AptMirrors.ALL.all { it.baseUrl.startsWith("http://") })

        val functions = AptMirrors.shellFunctions()
        AptMirrors.ALL.forEach { mirror ->
            assertTrue(functions.contains("'${mirror.baseUrl}') printf '%s' '${mirror.name}' ;;"))
        }

        val mirror = ContainerSetupScripts.configureAptMirror().body
        assertTrue(mirror.contains("maidroid_apt_write_sources 'http://mirrors.tuna.tsinghua.edu.cn'"))

        val install = ContainerSetupScripts.installSystemDependencies().body
        assertTrue(install.contains("maidroid_apt_install git ca-certificates curl"))
        assertTrue(install.contains("${AptMirrors.allNames}都试过了"))
    }

    @Test
    fun `only download failures switch mirrors`() {
        val functions = AptMirrors.shellFunctions()
        val fetchCheck = functions.substringAfter("maidroid_apt_fetch_failed() {").substringBefore("\n}")

        assertTrue(fetchCheck.contains("Hash Sum mismatch"))
        assertTrue(fetchCheck.contains("Temporary failure resolving"))
        // 空间满、手机时间不对时换源也没用，先排除掉
        val excluded = fetchCheck.lineSequence().first { it.contains("grep -qiE") }
        assertTrue(excluded.contains("No space left on device"))
        assertTrue(excluded.contains("is not valid yet"))
        assertFalse(excluded.contains("Hash Sum mismatch"))
    }

    @Test
    fun `ssh install uses the same fallback`() {
        val ssh = ContainerSshScripts.configure(port = 8022, rootPassword = "pw").body
        assertTrue(ssh.contains("maidroid_apt_install openssh-server"))
        assertTrue(ssh.startsWith(AptMirrors.shellFunctions()))
    }
}
