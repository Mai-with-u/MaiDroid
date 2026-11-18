// 远程清单签名验签（ECDSA P-256 -> ASN.1 DER，兼容 Android API 26+ 原生 SHA256withECDSA）、
// 去易混字符 6 位求助码生成与归一化、以及加盐哈希工具。

const DIAG_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

export interface SignedEnvelope {
  keyId: string;
  payload: string;
  signature: string;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export function base64ToBytes(base64: string): Uint8Array {
  const clean = base64.trim();
  const binary = atob(clean);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

// Web Crypto 的 ECDSA 输出固定 64 字节 IEEE P1363 (r || s)，而 Android 标准库 SHA256withECDSA 需要 ASN.1 DER 编码
function encodeAsn1Integer(slice: Uint8Array): Uint8Array {
  let start = 0;
  while (start < slice.length - 1 && slice[start] === 0) {
    start++;
  }
  const trimmed = slice.subarray(start);
  const needsZeroPad = (trimmed[0] & 0x80) !== 0;
  const len = trimmed.length + (needsZeroPad ? 1 : 0);
  const out = new Uint8Array(2 + len);
  out[0] = 0x02;
  out[1] = len;
  if (needsZeroPad) {
    out[2] = 0x00;
    out.set(trimmed, 3);
  } else {
    out.set(trimmed, 2);
  }
  return out;
}

export function p1363ToDer(sig: Uint8Array): Uint8Array {
  if (sig.length !== 64) {
    throw new Error(`Expected 64-byte P1363 signature, got ${sig.length}`);
  }
  const r = encodeAsn1Integer(sig.subarray(0, 32));
  const s = encodeAsn1Integer(sig.subarray(32, 64));
  const out = new Uint8Array(2 + r.length + s.length);
  out[0] = 0x30;
  out[1] = r.length + s.length;
  out.set(r, 2);
  out.set(s, 2 + r.length);
  return out;
}

// 将 ASN.1 DER 签名还原回 64 字节 P1363，供 Web Crypto 验证端到端签名一致性
export function derToP1363(der: Uint8Array): Uint8Array {
  if (der.length < 8 || der[0] !== 0x30 || der[1] + 2 !== der.length) {
    throw new Error("Invalid ASN.1 DER ECDSA signature");
  }
  const readInt = (offset: number): [Uint8Array, number] => {
    if (der[offset] !== 0x02) throw new Error("Expected ASN.1 INTEGER");
    const len = der[offset + 1];
    let body = der.subarray(offset + 2, offset + 2 + len);
    if (body.length === 33 && body[0] === 0x00) {
      body = body.subarray(1);
    }
    if (body.length > 32) throw new Error("ECDSA coordinate exceeds 32 bytes");
    const padded = new Uint8Array(32);
    padded.set(body, 32 - body.length);
    return [padded, offset + 2 + len];
  };
  const [r, next] = readInt(2);
  const [s, end] = readInt(next);
  if (end !== der.length) throw new Error("Trailing bytes in DER signature");
  const out = new Uint8Array(64);
  out.set(r, 0);
  out.set(s, 32);
  return out;
}

export async function signManifestPayload(
  payloadJson: string,
  pkcs8Base64: string,
  keyId: string
): Promise<SignedEnvelope> {
  const key = await crypto.subtle.importKey(
    "pkcs8",
    base64ToBytes(pkcs8Base64),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );
  const data = new TextEncoder().encode(payloadJson);
  const p1363 = new Uint8Array(
    await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, data)
  );
  return {
    keyId,
    payload: payloadJson,
    signature: bytesToBase64(p1363ToDer(p1363)),
  };
}

export async function verifyManifestEnvelope(
  envelope: SignedEnvelope,
  spkiBase64: string
): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      base64ToBytes(spkiBase64),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );
    const p1363 = derToP1363(base64ToBytes(envelope.signature));
    const data = new TextEncoder().encode(envelope.payload);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, p1363, data);
  } catch {
    return false;
  }
}

export function generateDiagCode(): string {
  const rand = new Uint8Array(6);
  crypto.getRandomValues(rand);
  let chars = "";
  for (let i = 0; i < 6; i++) {
    chars += DIAG_ALPHABET[rand[i] & 31];
  }
  return `${chars.slice(0, 3)}-${chars.slice(3, 6)}`;
}

// 兼容用户输入 `#m8k29f`、`M8K-29F`、`m8k 29f`，统一归一化为 `M8K-29F`
export function normalizeDiagCode(input: string): string | null {
  const cleaned = input.trim().replace(/^[#\s]+/, "").replace(/[-\s]/g, "").toUpperCase();
  if (cleaned.length !== 6) return null;
  for (const ch of cleaned) {
    if (!DIAG_ALPHABET.includes(ch)) return null;
  }
  return `${cleaned.slice(0, 3)}-${cleaned.slice(3, 6)}`;
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function timingSafeEqualText(left: string, right: string): Promise<boolean> {
  const [a, b] = await Promise.all([sha256Hex(left), sha256Hex(right)]);
  let diff = left.length ^ right.length;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
