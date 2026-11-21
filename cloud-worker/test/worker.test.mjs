import assert from "node:assert/strict";
import test from "node:test";
import { ADMIN_CONSOLE_MARKUP, ADMIN_CONSOLE_SCRIPT, buildConsoleBundle } from "../src/adminConsole.ts";
import { ADMIN_MANIFEST_EDITOR_SCRIPT } from "../src/adminManifestEditor.ts";
import { ADMIN_MANIFEST_PREVIEW_SCRIPT } from "../src/adminManifestPreview.ts";
import { ADMIN_TEAM_SCRIPT } from "../src/adminTeam.ts";
import { ADMIN_GATE_SCRIPT, ADMIN_HTML } from "../src/adminHtml.ts";
import { buildHeatmapMatrix, cnDayIndex, cnDayLabel, componentState, fillDailySeries } from "../src/adminOverview.ts";
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

  const prootAdvice = buildDiagnosticAdvice("P18-2AA", {
    appVersion: "0.3.0",
    device: "HUAWEI MLR-AL00",
    errorCode: "CT-PROOT/PROOT-182",
  });
  assert.equal(prootAdvice.replyText.includes("卓易通"), true);

  const oldMaiBotAdvice = buildDiagnosticAdvice("MBO-123", {
    appVersion: "0.3.0",
    device: "Xiaomi 24069RA21C",
    errorCode: "QQ-PRECHECK/MB-OLD",
  });
  assert.equal(oldMaiBotAdvice.replyText.includes("MaiBot 更新"), true);
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
  const audits = [];
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
    // 总览大屏：正式版待处理问题按分类聚合
    if (q.startsWith("SELECT category, COUNT(*) AS cnt, SUM(affected_installs) AS installs FROM error_issues")) {
      const map = new Map();
      for (const r of issues.values()) {
        if (r.build_type !== "release" || r.status !== "open") continue;
        const cur = map.get(r.category) ?? { category: r.category, cnt: 0, installs: 0 };
        cur.cnt += 1;
        cur.installs += r.affected_installs;
        map.set(r.category, cur);
      }
      return { results: Array.from(map.values()) };
    }
    if (q.startsWith("SELECT status, COUNT(*) AS cnt FROM error_issues WHERE build_type = 'release'")) {
      const map = new Map();
      for (const r of issues.values()) {
        if (r.build_type === "release") map.set(r.status, (map.get(r.status) ?? 0) + 1);
      }
      return { results: Array.from(map.entries()).map(([status, cnt]) => ({ status, cnt })) };
    }
    if (q === "SELECT MAX(last_seen_at) AS at FROM error_issues") {
      const all = Array.from(issues.values()).map((r) => r.last_seen_at);
      return { first: { at: all.length ? Math.max(...all) : null } };
    }
    if (q.includes("FROM manifest_config")) {
      return { first: null };
    }
    // 多用户鉴权：错误令牌查会话表查不到；审计写入只计数
    if (q.includes("FROM admin_sessions")) {
      return { first: null };
    }
    if (q.startsWith("INSERT INTO admin_audit")) {
      audits.push(args);
      return { changes: 1 };
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

  // 6. 总览大屏：待处理正式版问题计入 KPI，30 日条带与 7×24 热力形状固定，无管理口令一律 401
  const overviewResp = await worker.fetch(new Request("https://ops.8u.nz/admin/api/overview", { headers }), env, ctx);
  assert.equal(overviewResp.status, 200);
  assert.equal(overviewResp.headers.get("Cache-Control"), "no-store");
  const overview = await overviewResp.json();
  assert.equal(overview.kpi.openIssues, 1);
  assert.equal(overview.kpi.openInstalls, afterNew.issue.affected_installs);
  assert.equal(overview.daily.length, 30);
  assert.equal(overview.heatmap.length, 7);
  assert.equal(overview.heatmap[0].length, 24);
  assert.equal(overview.components.manifest.state, "none");
  assert.equal(overview.components.errorReport.state, "up");

  const anonOverview = await worker.fetch(new Request("https://ops.8u.nz/admin/api/overview"), env, ctx);
  assert.equal(anonOverview.status, 401);
});

test("overview helpers fill blank days by Beijing time and rotate heatmap to Monday-first", () => {
  // 2026-11-20 09:00 北京时间 = 01:00 UTC
  const now = Date.UTC(2026, 10, 20, 1, 0, 0) / 1000;
  const today = cnDayIndex(now);
  assert.equal(cnDayLabel(today), "11-20");
  // 北京时间 07:59 仍属于当天，UTC 前一天 23:59 也是北京时间当天
  assert.equal(cnDayIndex(Date.UTC(2026, 10, 19, 16, 0, 0) / 1000), today);
  assert.equal(cnDayIndex(Date.UTC(2026, 10, 19, 15, 59, 59) / 1000), today - 1);

  const series = fillDailySeries([{ d: today, installs: 10, err_installs: 2, events: 40 }], today);
  assert.equal(series.length, 30);
  assert.equal(series[29].day, "11-20");
  assert.equal(series[29].errRate, 0.2);
  assert.equal(series[0].installs, 0);
  assert.equal(series[0].day, "10-22");

  // SQL 侧周日为 0：周日 23 时应落到矩阵最后一行，周一 0 时落到第一行
  const matrix = buildHeatmapMatrix([
    { wd: 0, hr: 23, cnt: 5 },
    { wd: 1, hr: 0, cnt: 3 },
    { wd: 9, hr: 1, cnt: 99 },
  ]);
  assert.equal(matrix[6][23], 5);
  assert.equal(matrix[0][0], 3);
  assert.equal(matrix.flat().reduce((a, b) => a + b, 0), 8);

  assert.equal(componentState(null, now, 60), "none");
  assert.equal(componentState(now - 30, now, 60), "up");
  assert.equal(componentState(now - 120, now, 60), "idle");
});

test("admin gate page hides console until token verified and all inline scripts parse", async () => {
  // 闸门页与控制台脚本都必须是合法 JS（模板字符串转义失误会直接白屏）
  const match = ADMIN_HTML.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, "Expected <script> block in ADMIN_HTML");
  new Function(match[1]);
  new Function(ADMIN_CONSOLE_SCRIPT);
  assert.equal(match[1], ADMIN_GATE_SCRIPT);

  // 闸门页上不能出现任何控制台结构与管理接口路径（除校验口令用的 console 入口外）
  assert.equal(ADMIN_HTML.includes("errList"), false);
  assert.equal(ADMIN_HTML.includes("manifestEditor"), false);
  assert.equal(ADMIN_HTML.includes("/admin/api/errors"), false);
  assert.equal(ADMIN_HTML.includes("/admin/api/console"), true);
  assert.equal(ADMIN_CONSOLE_MARKUP.includes('data-act="errReload"'), true);
  assert.equal(ADMIN_CONSOLE_SCRIPT.includes("已完成引导"), true);

  const db = createMockD1();
  const env = { DB: db, ADMIN_TOKEN: "test-secret", IP_HASH_SALT: "salt-1" };
  const ctx = { waitUntil() {} };
  const page = await worker.fetch(new Request("https://ops.8u.nz/admin"), env, ctx);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("Content-Security-Policy"), /frame-ancestors 'none'/);
  assert.equal(page.headers.get("X-Frame-Options"), "DENY");

  const denied = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/console", { headers: { Authorization: "Bearer wrong" } }),
    env,
    ctx
  );
  assert.equal(denied.status, 401);
  const deniedBody = await denied.text();
  assert.equal(deniedBody.includes("errList"), false);

  const granted = await worker.fetch(
    new Request("https://ops.8u.nz/admin/api/console", { headers: { Authorization: "Bearer test-secret" } }),
    env,
    ctx
  );
  assert.equal(granted.status, 200);
  assert.equal(granted.headers.get("Cache-Control"), "no-store");
  const body = await granted.json();
  // ADMIN_TOKEN 是所有者：拿到的控制台包含清单编辑器、成员与审计分页
  const full = buildConsoleBundle({ maintainer: true, owner: true });
  assert.equal(body.markup, full.markup);
  assert.equal(body.script, full.script);
  assert.equal(body.markup.includes("<!--"), false);
  assert.equal(body.me.root, true);
  for (const s of [ADMIN_TEAM_SCRIPT, ADMIN_MANIFEST_PREVIEW_SCRIPT, ADMIN_MANIFEST_EDITOR_SCRIPT, body.script]) new Function(s);
});

test("manifest preview logic mirrors the Android client matching rules", () => {
  // 预览脚本不碰 DOM，直接在 Node 里执行，拿到挂在 window.MDC.mfp 上的纯函数
  const window = { MDC: {} };
  new Function("window", ADMIN_MANIFEST_PREVIEW_SCRIPT)(window);
  const P = window.MDC.mfp;

  // 动作白名单：与 parseRemoteActionTarget 一致
  assert.deepEqual(P.parseAction("open:qq_group"), { kind: "qq_group" });
  assert.deepEqual(P.parseAction("FreeUpStorage"), { kind: "cleanup" });
  assert.equal(P.parseAction("url:https://example.com/a").url, "https://example.com/a");
  assert.equal(P.parseAction("http://example.com"), null);
  assert.equal(P.parseAction("intent://x"), null);
  assert.equal(P.actionText("", "open:app_update"), "检查应用更新");
  assert.equal(P.actionText("去看看", "https://example.com/x"), "去看看");

  // 版本比较：与 VersionNumber 一致
  const v = (s) => P.parseVersion(s);
  assert.equal(P.cmpVersion(v("0.2.0-rc1"), v("0.2.0")), -1);
  assert.equal(P.cmpVersion(v("v0.2"), v("0.2.0")), 0);
  assert.equal(P.cmpVersion(v("0.10.0"), v("0.9.9")), 1);
  assert.equal(v("1.2.3-"), null);

  // 公告命中：逐条对齐 matches()
  const env = { appVersion: "0.2.5", manufacturer: "HONOR", sdkInt: 34, activeQqClient: "snowluma", setupComplete: true, now: 1000 };
  const base = { id: "a", title: "t", body: "b", filter: {} };
  assert.equal(P.annMissReason(base, env), null);
  assert.match(P.annMissReason({ ...base, expiresAt: 1000 }, env), /过期/);
  assert.equal(P.annMissReason({ ...base, filter: { manufacturers: ["honor"] } }, env), null);
  assert.match(P.annMissReason({ ...base, filter: { manufacturers: ["Xiaomi"] } }, env), /品牌/);
  assert.match(P.annMissReason({ ...base, filter: { activeQqClients: ["napcat"] } }, env), /协议端/);
  assert.match(P.annMissReason({ ...base, filter: { activeQqClients: ["snowluma"] } }, { ...env, activeQqClient: null }), /协议端/);
  assert.match(P.annMissReason({ ...base, filter: { minAppVersion: "0.2.6" } }, env), /低于/);
  assert.equal(P.annMissReason({ ...base, filter: { maxAppVersion: "bad" } }, env), null);
  assert.match(P.annMissReason({ ...base, filter: { onlyDuringOobe: true } }, env), /首次引导/);

  // 热规则：最后 400 行内字面匹配，按顺序取第一条
  const hints = [
    { id: "1", steps: ["QQ-INSTALL"], containsAny: ["ENOSPC"], title: "空间不足", advice: "清理" },
    { id: "2", steps: [], containsAny: ["enospc", "timeout"], title: "通用", advice: "重试" },
  ];
  assert.equal(P.matchHintIndex(hints, "QQ-INSTALL", "x\nError: ENOSPC no space"), 0);
  assert.equal(P.matchHintIndex(hints, "SL-INSTALL", "Error: ENOSPC"), 1);
  assert.equal(P.matchHintIndex(hints, "SL-INSTALL", "ENOSPC\n" + "ok\n".repeat(400)), -1);
  assert.equal(P.validateHint({ id: "x", title: "t", advice: "a", containsAny: [] }).some((e) => e[0] === "err"), true);
  assert.equal(P.validateAnn({ ...base, filter: { minAppVersion: "0.3", maxAppVersion: "0.2" } }, 0).some((e) => e[0] === "err"), true);
  assert.match(P.renderBanner({ ...base, title: "<b>x</b>" }), /&lt;b&gt;x/);
});
