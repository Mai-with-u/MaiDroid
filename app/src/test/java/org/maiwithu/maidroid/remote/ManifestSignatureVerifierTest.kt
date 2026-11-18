package org.maiwithu.maidroid.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestSignatureVerifierTest {

    // 由 cloud-worker/src/crypto.ts (Web Crypto ECDSA P-256 -> ASN.1 DER) 真实生成的跨端测试向量
    private val workerSpki =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEo4g6wHHLouOppENnhbQUhwPjGs4DUeAFrkwuDwTUQi6Zp0mMadSrmfo2/a0b3qs17MxjSJ8J19MozXH0OPWnBA=="
    private val workerPayload =
        """{"version":1,"generatedAt":1795000000,"announcements":[{"id":"ann-1","level":"info","title":"测试公告","body":"内容"}],"failureHints":[]}"""
    private val workerSig =
        "MEYCIQC/p+hm9MTbLEprC4Rkpj/qJNlcUbV2Tksdm4FGQCfCGwIhAMXdQEuLDbFgI4fYvwwUgf/ky4jewd5qBP6Q6aNM/zCm"

    @Test
    fun verifiesWebCryptoSignedEnvelopeAndRejectsTampering() {
        val envelope = Json.encodeToString(
            SignedManifestEnvelope(
                keyId = "test-k1",
                payload = workerPayload,
                signature = workerSig
            )
        )
        val parsed = ManifestSignatureVerifier.verifyAndParse(envelope, extraKeysSpec = "test-k1=$workerSpki")
        assertNotNull(parsed)
        assertEquals(1, parsed?.version)
        assertEquals("ann-1", parsed?.announcements?.single()?.id)

        // 篡改一个字符必须拒绝
        val tampered = Json.encodeToString(
            SignedManifestEnvelope(
                keyId = "test-k1",
                payload = workerPayload.replace("测试公告", "伪造公告"),
                signature = workerSig
            )
        )
        assertNull(ManifestSignatureVerifier.verifyAndParse(tampered, extraKeysSpec = "test-k1=$workerSpki"))

        // 未知 keyId 必须拒绝
        val unknownKey = Json.encodeToString(
            SignedManifestEnvelope(
                keyId = "unknown-key",
                payload = workerPayload,
                signature = workerSig
            )
        )
        assertNull(ManifestSignatureVerifier.verifyAndParse(unknownKey, extraKeysSpec = "test-k1=$workerSpki"))
    }
}
