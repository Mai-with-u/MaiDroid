package org.maiwithu.maidroid.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSshScriptsTest {

    @Test
    fun `cleanup pattern only matches sshd daemon processes and never configure shell`() {
        val cleanupBody = ContainerSshScripts.cleanup().body
        val pattern = ContainerSshScripts.SSHD_CLEANUP_PATTERN

        assertTrue(cleanupBody.contains("awk '/$pattern/ && !/awk/ { print \$1 }'"))
        assertTrue(pattern.contains("([ \\t]|\$)"))

        // awk 正则里的 \/ 转回普通 / 后在 JVM 侧按 ps -eo pid=,args= 的真实输出校验命中范围。
        val matcher = Regex(pattern.replace("\\/", "/"))

        assertTrue(matcher.containsMatchIn("  14841 /usr/sbin/sshd -D -e -p 2222"))
        assertTrue(matcher.containsMatchIn("14842 /usr/sbin/sshd"))
        assertTrue(matcher.containsMatchIn("  14850 sshd: root@pts/0"))

        assertFalse(
            matcher.containsMatchIn(
                "  13515 /bin/sh -c unset LD_LIBRARY_PATH; if [ ! -x /usr/sbin/sshd ]; then apt-get install openssh-server; fi"
            )
        )
        assertFalse(matcher.containsMatchIn("  14840 /bin/sh -c exec /usr/sbin/sshd -D -e -p 2222"))
        assertFalse(matcher.containsMatchIn("  14900 /usr/sbin/sshd-wrapper -p 2222"))
    }
}
