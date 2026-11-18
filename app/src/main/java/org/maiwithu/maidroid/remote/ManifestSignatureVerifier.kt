package org.maiwithu.maidroid.remote

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 远程清单的 ECDSA P-256（SHA256withECDSA）验签器。
 *
 * 无论是从 Worker 自定义域名还是第三方 GitHub 镜像拉回来的清单，都必须先验证原始 payload 字符串的签名，
 * 验签不通过一律丢弃，防止公共镜像污染篡改公告或伪造外链。
 * 支持内置公钥表加 Gradle 构建属性注入的调试公钥（形如 `keyId1=BASE64_SPKI,keyId2=BASE64_SPKI`）。
 */
internal object ManifestSignatureVerifier {

    // 生产环境公钥表（keyId -> X.509 SPKI Base64）：由 cloud-worker 的 `npm run keygen` 生成后填入，支持平滑轮换。
    private val BUILT_IN_PUBLIC_KEYS: Map<String, String> = mapOf(
        // 预置初始验签公钥槽位；绑定正式 Worker 域名时替换为自己私钥配对的 SPKI 公钥
        "2026-k1" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9xmWOUL21jbqmq4/Yen8x7r/+3wmkKFHXrm1JXQAh1kBUIH2kQgAoJrzo6y0+wDme2EGZF3PHHqYMLpCkUzdsA=="
    )

    fun parseKeySpecMap(extraSpec: String): Map<String, String> {
        if (extraSpec.isBlank()) return BUILT_IN_PUBLIC_KEYS
        val merged = BUILT_IN_PUBLIC_KEYS.toMutableMap()
        extraSpec.split(',').forEach { entry ->
            val trimmed = entry.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0 && eq < trimmed.lastIndex) {
                val id = trimmed.substring(0, eq).trim()
                val spki = trimmed.substring(eq + 1).trim()
                if (id.isNotEmpty() && spki.isNotEmpty()) {
                    merged[id] = spki
                }
            }
        }
        return merged
    }

    /** 验签并解析远程清单；格式不对、找不到 keyId 或签名不匹配均返回 null。 */
    fun verifyAndParse(envelopeJson: String, extraKeysSpec: String = ""): RemoteManifest? {
        val envelope = parseSignedEnvelope(envelopeJson) ?: return null
        val spkiBase64 = parseKeySpecMap(extraKeysSpec)[envelope.keyId] ?: return null
        if (!verifySignature(envelope.payload, envelope.signature, spkiBase64)) return null
        return parseRemoteManifest(envelope.payload)
    }

    internal fun verifySignature(payload: String, signatureBase64: String, spkiBase64: String): Boolean =
        runCatching {
            val keyBytes = Base64.getDecoder().decode(spkiBase64.trim())
            val sigBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload.toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        }.getOrDefault(false)
}
