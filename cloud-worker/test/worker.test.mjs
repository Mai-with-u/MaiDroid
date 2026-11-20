import assert from "node:assert/strict";
import test from "node:test";
import { ADMIN_HTML } from "../src/adminHtml.ts";
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
import {
  compareAppVersion,
  MAX_ERROR_CONTEXT_CHARS,
  resolveNextIssueStatus,
  shouldStoreSample,
  summarizeSampleDistribution,
  validateErrorReportPayload,
} from "../src/errorAggregation.ts";
import worker from "../src/index.ts";

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

test("validateErrorReportPayload normalizes fields, filters extra whitelist, and rejects bad inputs", () => {
  assert.equal(validateErrorReportPayload(null), null);
  assert.equal(
    validateErrorReportPayload({
      installId: "dev-1",
      fingerprint: "not-hex!",
      category: "crash",
      errorCode: "MD-CRASH",
      appVersion: "0.2.5",
      title: "boom",
    }),
    null
  );
  assert.equal(
    validateErrorReportPayload({
      installId: "dev-1",
      fingerprint: "abcdef0123456789",
      category: "chat_log",
      errorCode: "MD-CRASH",
      appVersion: "0.2.5",
      title: "boom",
    }),
    null
  );

  const longCtx = "  at frame\n" + "x".repeat(MAX_ERROR_CONTEXT_CHARS + 500);
  const valid = validateErrorReportPayload({
    installId: "dev-1",
    fingerprint: "ABCDEF0123456789ABCDEF0123456789",
    buildType: "DEBUG",
    category: "maibot_boot",
    errorCode: "MB-CRASH/PY-MODULE",
    appVersion: "0.2.5",
    versionCode: 10,
    manufacturer: "HONOR",
    model: "OXF-AN10",
    sdkInt: 31,
    title: "ModuleNotFoundError: foo",
    message: "启动失败",
    contextText: longCtx,
    extra: {
      oobeStep: "Install",
      setupComplete: true,
      secretToken: "must-be-dropped",
      availableStorageMb: 2048,
    },
  });

  assert.ok(valid);
  assert.equal(valid.fingerprint, "abcdef0123456789abcdef0123456789");
  assert.equal(valid.buildType, "debug");
  assert.equal(valid.contextText.length, MAX_ERROR_CONTEXT_CHARS);
  assert.equal(valid.contextText.startsWith("  at frame"), true);
  assert.deepEqual(valid.extra, {
    oobeStep: "Install",
    setupComplete: true,
    availableStorageMb: 2048,
  });
});

test("compareAppVersion and resolveNextIssueStatus handle regressions cleanly", () => {
  assert.equal(compareAppVersion("0.2.10", "0.2.5") > 0, true);
  assert.equal(compareAppVersion("v0.2.4", "0.2.4"), 0);
  assert.equal(resolveNextIssueStatus("resolved", "0.2.4", "0.2.4"), "resolved");
  assert.equal(resolveNextIssueStatus("resolved", "0.2.4", "0.2.3"), "resolved");
  assert.equal(resolveNextIssueStatus("resolved", "0.2.4", "0.2.5"), "open");
  assert.equal(resolveNextIssueStatus("ignored", "0.2.4", "0.9.0"), "ignored");
});

test("shouldStoreSample limits to 5 unless new version or manufacturer arrives", () => {
  const full = Array.from({ length: 5 }, () => ({ app_version: "0.2.4", manufacturer: "HONOR", sdk_int: 31 }));
  assert.equal(shouldStoreSample(full.slice(0, 4), { appVersion: "0.2.4", manufacturer: "HONOR" }), true);
  assert.equal(shouldStoreSample(full, { appVersion: "0.2.4", manufacturer: "HONOR" }), false);
  assert.equal(shouldStoreSample(full, { appVersion: "0.2.5", manufacturer: "HONOR" }), true);
  assert.equal(shouldStoreSample(full, { appVersion: "0.2.4", manufacturer: "Xiaomi" }), true);

  const dist = summarizeSampleDistribution([
    { app_version: "0.2.4", manufacturer: "HONOR", sdk_int: 31 },
    { app_version: "0.2.5", manufacturer: "Xiaomi", sdk_int: 34 },
  ]);
  assert.equal(dist.versions.length, 2);
  assert.equal(dist.manufacturers[0].count, 1);
  assert.equal(dist.sdks[0].label.startsWith("API "), true);
});

// 轻量内存 D1 mock，覆盖自动报错汇聚与管理台筛选/隔离接口所需的 SQL 语句
function createMockD1() {
  const rateLimits = new Map();
  const issues = new Map();
  const installs = new Map();
  const samples = [];
  let sampleSeq = 1;

  const normalize = (sql) => sql.replace(/\s+/g, " ").trim();

  function execQuery(sql, args) {
    const q = normalize(sql);

    if (q.startsWith("SELECT count, reset_at FROM rate_limits WHERE bucket_key = ?")) {
      return { first: rateLimits.get(args[0]) ?? null };
    }
    if (q.startsWith("INSERT INTO rate_limits")) {
      rateLimits.set(args[0], { count: 1, reset_at: args[1] });
      return { changes: 1 };
    }
    if (q.startsWith("UPDATE rate_limits SET count = count + 1")) {
      const row = rateLimits.get(args[0]);
      if (row) row.count += 1;
      return { changes: row ? 1 : 0 };
    }

    if (q === "SELECT status, last_version FROM error_issues WHERE fingerprint = ? AND build_type = ?") {
      const row = issues.get(`${args[1]}:${args[0]}`);
      return { first: row ? { status: row.status, last_version: row.last_version } : null };
    }
    if (q.startsWith("INSERT OR IGNORE INTO error_issue_installs")) {
      const [fp, bt, iid, seen] = args;
      const k = `${bt}:${fp}:${iid}`;
      if (installs.has(k)) return { changes: 0 };
      installs.set(k, { fingerprint: fp, build_type: bt, install_id: iid, last_seen_at: seen });
      return { changes: 1 };
    }
    if (q.startsWith("UPDATE error_issue_installs SET last_seen_at = ?")) {
      const [seen, fp, bt, iid] = args;
      const row = installs.get(`${bt}:${fp}:${iid}`);
      if (row) row.last_seen_at = seen;
      return { changes: row ? 1 : 0 };
    }
    if (q.startsWith("INSERT INTO error_issues")) {
      const [fp, bt, cat, code, title, firstAt, lastAt, firstVer, lastVer] = args;
      issues.set(`${bt}:${fp}`, {
        fingerprint: fp,
        build_type: bt,
        category: cat,
        error_code: code,
        title,
        status: "open",
        first_seen_at: firstAt,
        last_seen_at: lastAt,
        first_version: firstVer,
        last_version: lastVer,
        total_count: 1,
        affected_installs: 1,
      });
      return { changes: 1 };
    }
    if (q.startsWith("UPDATE error_issues SET last_seen_at = ?")) {
      const [lastAt, lastVer, title, code, status, addInstall, fp, bt] = args;
      const row = issues.get(`${bt}:${fp}`);
      if (!row) return { changes: 0 };
      row.last_seen_at = lastAt;
      row.last_version = lastVer;
      row.title = title;
      row.error_code = code;
      row.status = status;
      row.total_count += 1;
      row.affected_installs += addInstall;
      return { changes: 1 };
    }
    if (q.startsWith("SELECT app_version, manufacturer FROM error_samples")) {
      const [fp, bt, limit] = args;
      const list = samples
        .filter((s) => s.fingerprint === fp && s.build_type === bt)
        .sort((a, b) => b.created_at - a.created_at || b.id - a.id)
        .slice(0, limit)
        .map((s) => ({ app_version: s.app_version, manufacturer: s.manufacturer }));
      return { results: list };
    }
    if (q.startsWith("INSERT INTO error_samples")) {
      const [fp, bt, createdAt, ver, vcode, mfg, model, sdk, code, msg, ctxText, extra] = args;
      samples.push({
        id: sampleSeq++,
        fingerprint: fp,
        build_type: bt,
        created_at: createdAt,
        app_version: ver,
        version_code: vcode,
        manufacturer: mfg,
        model,
        sdk_int: sdk,
        error_code: code,
        message: msg,
        context_text: ctxText,
        extra_json: extra,
      });
      return { changes: 1 };
    }
    if (q.startsWith("DELETE FROM error_samples WHERE fingerprint = ? AND build_type = ? AND id NOT IN")) {
      const [fp, bt, , , limit] = args;
      const keepIds = new Set(
        samples
          .filter((s) => s.fingerprint === fp && s.build_type === bt)
          .sort((a, b) => b.created_at - a.created_at || b.id - a.id)
          .slice(0, limit)
          .map((s) => s.id)
      );
      let removed = 0;
      for (let i = samples.length - 1; i >= 0; i--) {
        const s = samples[i];
        if (s.fingerprint === fp && s.build_type === bt && !keepIds.has(s.id)) {
          samples.splice(i, 1);
          removed++;
        }
      }
      return { changes: removed };
    }
    if (q.startsWith("DELETE FROM error_issue_installs WHERE last_seen_at < ?")) {
      return { changes: 0 };
    }
    if (q.startsWith("SELECT fingerprint, build_type, category, error_code, title, status, first_seen_at") && q.includes("ORDER BY affected_installs DESC")) {
      let rows = Array.from(issues.values());
      let argIdx = 0;
      if (q.includes("build_type = 'release'")) {
        rows = rows.filter((r) => r.build_type === "release");
      }
      if (q.includes("status = ?")) {
        const st = args[argIdx++];
        rows = rows.filter((r) => r.status === st);
      }
      if (q.includes("category = ?")) {
        const cat = args[argIdx++];
        rows = rows.filter((r) => r.category === cat);
      }
      rows.sort((a, b) => b.affected_installs - a.affected_installs || b.total_count - a.total_count || b.last_seen_at - a.last_seen_at);
      return { results: rows.slice(0, 50) };
    }
    if (q.startsWith("SELECT build_type, status, COUNT(*) AS cnt FROM error_issues")) {
      const map = new Map();
      for (const r of issues.values()) {
        const k = `${r.build_type}|${r.status}`;
        map.set(k, (map.get(k) ?? 0) + 1);
      }
      const results = Array.from(map.entries()).map(([k, cnt]) => {
        const [build_type, status] = k.split("|");
        return { build_type, status, cnt };
      });
      return { results };
    }
    if (q === "DELETE FROM error_samples WHERE build_type = 'debug'") {
      let changes = 0;
      for (let i = samples.length - 1; i >= 0; i--) {
        if (samples[i].build_type === "debug") { samples.splice(i, 1); changes++; }
      }
      return { changes };
    }
    if (q === "DELETE FROM error_issue_installs WHERE build_type = 'debug'") {
      let changes = 0;
      for (const [k, v] of installs.entries()) {
        if (v.build_type === "debug") { installs.delete(k); changes++; }
      }
      return { changes };
    }
    if (q === "DELETE FROM error_issues WHERE build_type = 'debug'") {
      let changes = 0;
      for (const [k, v] of issues.entries()) {
        if (v.build_type === "debug") { issues.delete(k); changes++; }
      }
      return { changes };
    }
    if (q.startsWith("SELECT fingerprint, build_type, category") && q.includes("WHERE fingerprint = ? AND build_type = ?")) {
      return { first: issues.get(`${args[1]}:${args[0]}`) ?? null };
    }
    if (q.startsWith("SELECT id, created_at, app_version") && q.includes("FROM error_samples WHERE fingerprint = ? AND build_type = ?")) {
      const [fp, bt, limit] = args;
      const results = samples
        .filter((s) => s.fingerprint === fp && s.build_type === bt)
        .sort((a, b) => b.created_at - a.created_at || b.id - a.id)
        .slice(0, limit);
      return { results };
    }
    if (q === "UPDATE error_issues SET status = ? WHERE fingerprint = ? AND build_type = ?") {
      const [status, fp, bt] = args;
      const row = issues.get(`${bt}:${fp}`);
      if (!row) return { changes: 0 };
      row.status = status;
      return { changes: 1 };
    }
    if (q === "SELECT COUNT(*) AS cnt FROM error_issues WHERE build_type = 'release' AND status = 'open'") {
      const cnt = Array.from(issues.values()).filter((r) => r.build_type === "release" && r.status === "open").length;
      return { first: { cnt } };
    }
    if (q.includes("FROM telemetry_events")) {
      return { first: { uniq: 0, done: 0 }, results: [] };
    }
    if (q.includes("FROM diag_snapshots")) {
      return { first: { cnt: 0 } };
    }
    throw new Error(`Unexpected SQL in mock D1: ${q}`);
  }

  return {
    prepare(sql) {
      let bound = [];
      const stmt = {
        bind(...args) {
          bound = args;
          return stmt;
        },
        async first() {
          return execQuery(sql, bound).first ?? null;
        },
        async all() {
          return { results: execQuery(sql, bound).results ?? [] };
        },
        async run() {
          return { meta: { changes: execQuery(sql, bound).changes ?? 0 } };
        },
      };
      return stmt;
    },
  };
}

test("Worker /v1/error-report aggregates issues, isolates debug builds, and supports admin lifecycle", async () => {
  const db = createMockD1();
  const env = { DB: db, ADMIN_TOKEN: "test-secret", IP_HASH_SALT: "salt-1" };
  const ctx = { waitUntil() {} };
  const headers = { Authorization: "Bearer test-secret", "Content-Type": "application/json" };
  const fp = "0123456789abcdef0123456789abcdef";

  // 1. 先报一条 debug，再报两条同一指纹不同设备的 release，以及同一设备重复报一次
  const postReport = (body) =>
    worker.fetch(
      new Request("https://ops.8u.nz/v1/error-report", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "1.2.3.4" },
        body: JSON.stringify(body),
      }),
      env,
      ctx
    );

  assert.equal(
    (await postReport({
      installId: "dbg-1",
      fingerprint: fp,
      buildType: "debug",
      category: "crash",
      errorCode: "MD-CRASH",
      appVersion: "0.2.5",
      title: "Debug NPE",
      contextText: "at Foo.bar",
    })).status,
    200
  );

  for (const [iid, mfg, ver] of [
    ["rel-a", "HONOR", "0.2.4"],
    ["rel-a", "HONOR", "0.2.4"],
    ["rel-b", "Xiaomi", "0.2.4"],
  ]) {
    assert.equal(
      (await postReport({
        installId: iid,
        fingerprint: fp,
        buildType: "release",
        category: "crash",
        errorCode: "MD-CRASH",
        appVersion: ver,
        manufacturer: mfg,
        title: "Release NPE",
        contextText: "at MainScreen.kt:42",
      })).status,
      200
    );
  }

  // 2. 默认查询只见 release：影响 2 台设备、总计 3 次；摘要告知有 1 条 debug
  const listDefaultResp = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/errors", { headers }),
    env,
    ctx
  );
  assert.equal(listDefaultResp.status, 200);
  const listDefault = await listDefaultResp.json();
  assert.equal(listDefault.issues.length, 1);
  assert.equal(listDefault.issues[0].build_type, "release");
  assert.equal(listDefault.issues[0].affected_installs, 2);
  assert.equal(listDefault.issues[0].total_count, 3);
  assert.equal(listDefault.summary.openCount, 1);
  assert.equal(listDefault.summary.debugCount, 1);

  // 3. 勾选 includeDebug=1 后两者都出现
  const listWithDbgResp = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/errors?includeDebug=1", { headers }),
    env,
    ctx
  );
  const listWithDbg = await listWithDbgResp.json();
  assert.equal(listWithDbg.issues.length, 2);

  // 4. 详情与状态改为 resolved -> 同版本再报保持 resolved -> 新版本 0.2.5 再报自动重开为 open
  const detailResp = await worker.fetch(
    new Request(`https://ops.8u.nz/admin/api/errors/release/${fp}`, { headers }),
    env,
    ctx
  );
  const detail = await detailResp.json();
  assert.equal(detail.samples.length, 3);

  const patchResp = await worker.fetch(
    new Request(`https://ops.8u.nz/admin/api/errors/release/${fp}`, {
      method: "PATCH",
      headers,
      body: JSON.stringify({ status: "resolved" }),
    }),
    env,
    ctx
  );
  assert.equal(patchResp.status, 200);

  await postReport({
    installId: "rel-c",
    fingerprint: fp,
    buildType: "release",
    category: "crash",
    errorCode: "MD-CRASH",
    appVersion: "0.2.4",
    title: "Release NPE",
  });
  let afterOld = await (await worker.fetch(new Request(`https://ops.8u.nz/admin/api/errors/release/${fp}`, { headers }), env, ctx)).json();
  assert.equal(afterOld.issue.status, "resolved");

  await postReport({
    installId: "rel-d",
    fingerprint: fp,
    buildType: "release",
    category: "crash",
    errorCode: "MD-CRASH",
    appVersion: "0.2.5",
    title: "Release NPE",
  });
  let afterNew = await (await worker.fetch(new Request(`https://ops.8u.nz/admin/api/errors/release/${fp}`, { headers }), env, ctx)).json();
  assert.equal(afterNew.issue.status, "open");

  // 5. 清空 debug 测试数据，release 记录纹丝不动
  const delResp = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/errors/debug", { method: "DELETE", headers }),
    env,
    ctx
  );
  const delJson = await delResp.json();
  assert.equal(delJson.deletedIssues, 1);

  const statsResp = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/stats", { headers }),
    env,
    ctx
  );
  const stats = await statsResp.json();
  assert.equal(stats.openReleaseIssues, 1);
});

test("ADMIN_HTML inline script has valid JavaScript syntax and safe dataset handlers", () => {
  const match = ADMIN_HTML.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, "Expected <script> block in ADMIN_HTML");
  new Function(match[1]);
  assert.equal(ADMIN_HTML.includes("data-bt="), true);
  assert.equal(ADMIN_HTML.includes("openErrorDetail(this.dataset.bt,this.dataset.fp)"), true);
});
