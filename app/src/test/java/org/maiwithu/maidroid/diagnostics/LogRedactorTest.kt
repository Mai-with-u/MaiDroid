package org.maiwithu.maidroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    private val redactor = LogRedactor(
        listOf(
            LogRedactor.Secret("WebUI令牌", "a1b2c3d4e5f6"),
            LogRedactor.Secret("QQ号", "123456789"),
            LogRedactor.Secret("太短", "ab")
        )
    )

    @Test
    fun knownSecretsAreReplacedExactly() {
        assertEquals(
            "打开 http://127.0.0.1:8001/auth?token=<已隐藏:WebUI令牌>",
            redactor.redact("打开 http://127.0.0.1:8001/auth?token=a1b2c3d4e5f6")
        )
        // QQ 号只换完整的一串数字，不动更长数字里相同的一段；太短的值不替换
        assertEquals(
            "登录 <已隐藏:QQ号> 成功，消息 91234567890 ab",
            redactor.redact("登录 123456789 成功，消息 91234567890 ab")
        )
    }

    @Test
    fun commonSecretFormatsAreMasked() {
        assertEquals("key sk-<已隐藏>", redactor.redact("key sk-abcdefghijklmnop1234"))
        assertEquals("Authorization: Bearer <已隐藏>", redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x"))
        assertEquals("WebUI access token: <已隐藏>", redactor.redact("WebUI access token: zzzzzzyyyyyy"))
        assertEquals("api_key = \"<已隐藏>\"", redactor.redact("api_key = \"my-secret-key\""))
        assertEquals("GET /x?key=<已隐藏>&page=2", redactor.redact("GET /x?key=abcdef&page=2"))
    }

    @Test
    fun qqNumbersAndPhoneNumbersAreMasked() {
        assertEquals("收到群号 <QQ号> 的消息", redactor.redact("收到群号 987654321 的消息"))
        assertEquals("\"user_id\": <QQ号>", redactor.redact("\"user_id\": 55556666"))
        assertEquals("电话 <手机号>", redactor.redact("电话 13812345678"))
        // 毫秒时间戳不是手机号
        assertEquals("ts=1793536200250", redactor.redact("ts=1793536200250"))
    }

    @Test
    fun publicAddressesAreMaskedButLocalAndDnsKept() {
        assertEquals(
            "[DNS] nameservers: 192.168.1.1 223.5.5.5 <IP>",
            redactor.redact("[DNS] nameservers: 192.168.1.1 223.5.5.5 61.139.2.69")
        )
        assertEquals("listen 127.0.0.1:8001 10.0.2.15", redactor.redact("listen 127.0.0.1:8001 10.0.2.15"))
        // 版本号不像 IP（某一段超过 255 或不是四段）
        assertEquals("Chromium 132.0.6834.163 / 1.3.19", redactor.redact("Chromium 132.0.6834.163 / 1.3.19"))
    }

    @Test
    fun placeholdersAreNotRedactedTwice() {
        val once = redactor.redact("token: a1b2c3d4e5f6")
        assertEquals(once, redactor.redact(once))
        assertFalse(once.contains("a1b2c3d4e5f6"))
    }
}
