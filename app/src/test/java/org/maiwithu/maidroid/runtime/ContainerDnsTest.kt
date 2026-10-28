package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerDnsTest {

    private fun nameservers(conf: String): List<String> =
        conf.lines().filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }

    @Test
    fun `system dns goes first and public dns follows without duplicates`() {
        val conf = ContainerDns.resolvConf(listOf("192.168.1.1", "223.5.5.5"))

        assertEquals(
            listOf("192.168.1.1", "223.5.5.5", "114.114.114.114", "8.8.8.8", "1.1.1.1"),
            nameservers(conf)
        )
    }

    @Test
    fun `link local ipv6 and odd values are dropped`() {
        val conf = ContainerDns.resolvConf(
            listOf("fe80::1%wlan0", "FE80::2", "2408:8000::8", "evil\nnameserver 6.6.6.6", " 10.0.0.1 ")
        )

        assertEquals(listOf("2408:8000::8", "10.0.0.1") + ContainerDns.PUBLIC_SERVERS, nameservers(conf))
    }

    @Test
    fun `no system dns falls back to public dns in order without rotate`() {
        val conf = ContainerDns.resolvConf(emptyList())

        assertEquals(ContainerDns.PUBLIC_SERVERS, nameservers(conf))
        assertEquals(false, conf.contains("rotate"))
    }
}
