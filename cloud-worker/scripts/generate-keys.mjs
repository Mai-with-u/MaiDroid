// 生成远程清单签名用的 ECDSA P-256 密钥对：
// 私钥（PKCS#8 Base64）写入 Cloudflare Secret `SIGNING_PRIVATE_KEY`；
// 公钥（X.509 SPKI Base64）写入 Android 客户端 `ManifestSignatureVerifier.kt` 的内置公钥表。

const keyPair = await crypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"]
);

const pkcs8Bytes = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey));
const spkiBytes = new Uint8Array(await crypto.subtle.exportKey("spki", keyPair.publicKey));

const toBase64 = (bytes) => Buffer.from(bytes).toString("base64");

console.log("=== ECDSA P-256 密钥对已生成 ===");
console.log("\n[1] 私钥（请执行 `npx wrangler secret put SIGNING_PRIVATE_KEY` 后粘贴，切勿提交到仓库）：");
console.log(toBase64(pkcs8Bytes));
console.log("\n[2] 公钥（填入 Android 端 ManifestSignatureVerifier.kt 对应 keyId）：");
console.log(toBase64(spkiBytes));
