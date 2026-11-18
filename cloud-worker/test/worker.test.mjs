import assert from "node:assert/strict";
import test from "node:test";
import { buildDiagnosticAdvice } from "../src/advisor.ts";
import {
  bytesToBase64,
  derToP1363,
  generateDiagCode,
  normalizeDiagCode,
  p1363ToDer,
  signManifestPayload,
  verifyManifestEnvelope,
} from "../src/crypto.ts";

test("ECDSA P-256 signs payload into ASN.1 DER and verifies cleanly", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );
  const pkcs8 = bytesToBase64(new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey)));
  const spki = bytesToBase64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));

  const payload = JSON.stringify({ version: 3, announcements: [{ id: "a1", title: "通知" }], failureHints: [] });
  const envelope = await signManifestPayload(payload, pkcs8, "2026-k1");

  assert.equal(envelope.keyId, "2026-k1");
  assert.equal(await verifyManifestEnvelope(envelope, spki), true);
  assert.equal(await verifyManifestEnvelope({ ...envelope, payload: payload + " " }, spki), false);
});

test("P1363 <-> ASN.1 DER handles high-bit padding and leading zeros", () => {
  const raw = new Uint8Array(64);
  raw[0] = 0x00;
  raw[1] = 0x85; // 去掉前导 0 后最高位为 1，必须补 0x00 符号字节
  raw[32] = 0xff;
  const der = p1363ToDer(raw);
  assert.equal(der[0], 0x30);
  const restored = derToP1363(der);
  assert.deepEqual(restored, raw);
});

test("Diag code generation and normalization strip # and dashes", () => {
  for (let i = 0; i < 20; i++) {
    const code = generateDiagCode();
    assert.match(code, /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{3}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{3}$/);
    assert.equal(normalizeDiagCode(`#${code.toLowerCase()}`), code);
  }
  assert.equal(normalizeDiagCode("0O1-I10"), null); // 易混淆字符不接受
});

test("Diagnostic advisor surfaces missing battery whitelist, low disk, and DNS steps", () => {
  const result = buildDiagnosticAdvice("M8K-29F", {
    appVersion: "0.2.4",
    device: "HONOR OXF-AN10",
    availableStorageMb: 180,
    permissions: { storage: true, batteryIgnored: false, notifications: true },
    errorCode: "UV-SYNC/NET-DNS",
    lastExitReason: "其他（厂商系统的后台清理多半记在这里）",
  });
  assert.equal(result.findings.length >= 3, true);
  assert.equal(result.replyText.includes("#M8K-29F"), true);
  assert.equal(result.replyText.includes("清理空间"), true);
  assert.equal(result.replyText.includes("私人 DNS"), true);
  assert.equal(result.replyText.includes("忽略电池优化"), true);
});
