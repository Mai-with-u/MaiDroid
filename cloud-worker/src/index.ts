// MaiDroid 远程运维 Cloudflare Worker 入口：
// 1. GET  /v1/manifest.json — 下发 ECDSA 签名的远程公告与动态排障热规则（带边缘缓存与 ETag）
// 2. POST /v1/diag          — 接收 Gzip 脱敏诊断快照，校验防爆后入库 D1 并返回 6 位求助码
// 3. POST /v1/telemetry     — 接收无感匿名枚举心跳
// 4. GET  /admin & /admin/api/* — 单页管理后台

import { ADMIN_HTML } from "./adminHtml.ts";
import { buildDiagnosticAdvice, type SnapshotInput } from "./advisor.ts";
import {
  generateDiagCode,
  normalizeDiagCode,
  sha256Hex,
  signManifestPayload,
  timingSafeEqualText,
} from "./crypto.ts";

export interface Env {
  DB: D1Database;
  SIGNING_KEY_ID?: string;
  SIGNING_PRIVATE_KEY?: string;
  ADMIN_TOKEN?: string;
  IP_HASH_SALT?: string;
}

const MAX_DIAG_GZIP_BYTES = 128 * 1024;
const MAX_DIAG_UNCOMPRESSED_BYTES = 1024 * 1024;
const MAX_TELEMETRY_BYTES = 2 * 1024;
const DIAG_TTL_SECONDS = 7 * 86400;
const TELEMETRY_TTL_SECONDS = 30 * 86400;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const { pathname } = url;

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    try {
      if (pathname === "/v1/manifest.json" && request.method === "GET") {
        return await handleGetManifest(request, env, ctx);
      }
      if (pathname === "/v1/diag" && request.method === "POST") {
        return await handleUploadDiag(request, env, ctx);
      }
      if (pathname === "/v1/telemetry" && request.method === "POST") {
        return await handleTelemetry(request, env, ctx);
      }
      if (pathname === "/admin" && request.method === "GET") {
        return new Response(ADMIN_HTML, {
          headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
        });
      }
      if (pathname.startsWith("/admin/api/")) {
        return await handleAdminApi(request, env, url);
      }
      return jsonResponse({ error: "Not found" }, 404);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Internal error";
      return jsonResponse({ error: msg }, 500);
    }
  },
};

async function handleGetManifest(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const cache = caches.default;
  const cacheKey = new Request(new URL("/v1/manifest.json", request.url).toString(), { method: "GET" });
  const cached = await cache.match(cacheKey);

  if (cached) {
    const etag = cached.headers.get("ETag");
    if (etag && request.headers.get("If-None-Match") === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag, ...corsHeaders() } });
    }
    return cached;
  }

  const row = await env.DB.prepare(
    "SELECT signed_envelope_json, etag FROM manifest_config WHERE id = 1"
  ).first<{ signed_envelope_json: string; etag: string }>();

  let bodyJson: string;
  let etag: string;
  if (row) {
    bodyJson = row.signed_envelope_json;
    etag = row.etag;
  } else {
    if (!env.SIGNING_PRIVATE_KEY) {
      return jsonResponse({ error: "SIGNING_PRIVATE_KEY not configured" }, 503);
    }
    const now = Math.floor(Date.now() / 1000);
    const emptyPayload = JSON.stringify({ version: 0, generatedAt: now, announcements: [], failureHints: [] });
    const signed = await signManifestPayload(emptyPayload, env.SIGNING_PRIVATE_KEY, env.SIGNING_KEY_ID || "2026-k1");
    bodyJson = JSON.stringify(signed);
    etag = `"${(await sha256Hex(bodyJson)).slice(0, 16)}"`;
  }

  if (request.headers.get("If-None-Match") === etag) {
    return new Response(null, { status: 304, headers: { ETag: etag, ...corsHeaders() } });
  }

  const response = new Response(bodyJson, {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "public, max-age=60, s-maxage=300",
      ETag: etag,
      ...corsHeaders(),
    },
  });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

async function handleUploadDiag(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const lenHeader = Number(request.headers.get("Content-Length") || "0");
  if (lenHeader > MAX_DIAG_GZIP_BYTES) {
    return jsonResponse({ error: "诊断快照体积超过 128 KB 上限" }, 413);
  }
  if (!(await checkRateLimit(request, env, "diag", 5, 600))) {
    return jsonResponse({ error: "请求过于频繁，请稍后再试" }, 429);
  }

  const rawBytes = new Uint8Array(await request.arrayBuffer());
  if (rawBytes.length < 20 || rawBytes.length > MAX_DIAG_GZIP_BYTES) {
    return jsonResponse({ error: "诊断快照大小不合法" }, 400);
  }
  if (rawBytes[0] !== 0x1f || rawBytes[1] !== 0x8b) {
    return jsonResponse({ error: "仅接受 Gzip 压缩快照" }, 400);
  }

  // 流式解压并卡住 1 MB 硬上限，防止高压缩比炸弹耗尽 Worker 内存
  const parsed = JSON.parse(await gunzipLimitedText(rawBytes, MAX_DIAG_UNCOMPRESSED_BYTES)) as SnapshotInput;
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = now + DIAG_TTL_SECONDS;
  const appVersion = safeStr(parsed.appVersion, 32) || "unknown";
  const device = safeStr(parsed.device, 96) || "unknown";
  const sdkInt = Number.isInteger(parsed.sdkInt) ? Number(parsed.sdkInt) : 0;
  const errorCode = safeStr(parsed.errorCode, 48);
  const summaryJson = JSON.stringify({
    availableStorageMb: parsed.availableStorageMb ?? null,
    availableMemMb: parsed.availableMemMb ?? null,
    permissions: parsed.permissions ?? {},
    failureMessage: safeStr(parsed.failureMessage, 240),
    lastExitReason: safeStr(parsed.lastExitReason, 120),
  });

  let savedCode: string | null = null;
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = generateDiagCode();
    const res = await env.DB.prepare(
      `INSERT OR IGNORE INTO diag_snapshots
       (code, created_at, expires_at, app_version, device, sdk_int, error_code, summary_json, payload_gzip)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(candidate, now, expiresAt, appVersion, device, sdkInt, errorCode, summaryJson, rawBytes)
      .run();
    if ((res.meta?.changes ?? 0) > 0) {
      savedCode = candidate;
      break;
    }
  }
  if (!savedCode) {
    return jsonResponse({ error: "生成求助码冲突，请重试" }, 503);
  }

  ctx.waitUntil(
    env.DB.prepare("DELETE FROM diag_snapshots WHERE expires_at < ?").bind(now).run()
  );
  return jsonResponse({ code: savedCode, expiresAt }, 200);
}

async function handleTelemetry(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const rawText = await request.text();
  if (rawText.length > MAX_TELEMETRY_BYTES) {
    return jsonResponse({ error: "Payload too large" }, 413);
  }
  if (!(await checkRateLimit(request, env, "tel", 30, 3600))) {
    return jsonResponse({ error: "Rate limited" }, 429);
  }

  const body = JSON.parse(rawText) as Record<string, unknown>;
  const installId = safeStr(body.installId, 64);
  const eventType = safeStr(body.event, 32);
  const appVersion = safeStr(body.appVersion, 32);
  if (!installId || !eventType || !appVersion) {
    return jsonResponse({ error: "Missing required fields" }, 400);
  }

  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare(
    `INSERT INTO telemetry_events
     (created_at, install_id, event_type, app_version, manufacturer, model, sdk_int,
      oobe_step, setup_complete, active_qq_client, error_code, last_exit_reason)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  )
    .bind(
      now,
      installId,
      eventType,
      appVersion,
      safeStr(body.manufacturer, 48) || "unknown",
      safeStr(body.model, 64) || "unknown",
      typeof body.sdkInt === "number" ? Math.trunc(body.sdkInt) : 0,
      safeStr(body.oobeStep, 24),
      body.setupComplete === true ? 1 : 0,
      safeStr(body.activeQqClient, 24),
      safeStr(body.errorCode, 48),
      safeStr(body.lastExitReason, 80)
    )
    .run();

  if (Math.random() < 0.05) {
    ctx.waitUntil(
      env.DB.prepare("DELETE FROM telemetry_events WHERE created_at < ?")
        .bind(now - TELEMETRY_TTL_SECONDS)
        .run()
    );
  }
  return jsonResponse({ ok: true }, 200);
}

async function handleAdminApi(request: Request, env: Env, url: URL): Promise<Response> {
  if (!env.ADMIN_TOKEN) {
    return jsonResponse({ error: "ADMIN_TOKEN 未配置" }, 503);
  }
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token || !(await timingSafeEqualText(token, env.ADMIN_TOKEN))) {
    if (!(await checkRateLimit(request, env, "admin_fail", 8, 900))) {
      return jsonResponse({ error: "鉴权失败次数过多，请 15 分钟后再试" }, 429);
    }
    return jsonResponse({ error: "ADMIN_TOKEN 无效" }, 401);
  }

  const sub = url.pathname.slice("/admin/api/".length);
  if (sub.startsWith("diag/") && request.method === "GET") {
    const code = normalizeDiagCode(decodeURIComponent(sub.slice("diag/".length)));
    if (!code) return jsonResponse({ error: "求助码格式不正确（应为 6 位字符）" }, 400);
    const row = await env.DB.prepare(
      "SELECT code, created_at, app_version, device, sdk_int, error_code, payload_gzip FROM diag_snapshots WHERE code = ?"
    )
      .bind(code)
      .first<{
        code: string;
        created_at: number;
        app_version: string;
        device: string;
        sdk_int: number;
        error_code: string | null;
        payload_gzip: ArrayBuffer;
      }>();
    if (!row) return jsonResponse({ error: `未找到求助码 #${code}（可能已过期或输入有误）` }, 404);
    const snapshot = JSON.parse(
      await gunzipLimitedText(new Uint8Array(row.payload_gzip), MAX_DIAG_UNCOMPRESSED_BYTES)
    ) as SnapshotInput & Record<string, unknown>;
    return jsonResponse({
      code: row.code,
      createdAt: row.created_at,
      appVersion: row.app_version,
      device: row.device,
      sdkInt: row.sdk_int,
      errorCode: row.error_code,
      advice: buildDiagnosticAdvice(row.code, snapshot),
      snapshot,
    });
  }

  if (sub === "manifest" && request.method === "GET") {
    const row = await env.DB.prepare("SELECT version, payload_json FROM manifest_config WHERE id = 1").first<{
      version: number;
      payload_json: string;
    }>();
    return jsonResponse({
      version: row?.version ?? 0,
      payload: row ? JSON.parse(row.payload_json) : { announcements: [], failureHints: [] },
    });
  }

  if (sub === "manifest" && request.method === "PUT") {
    if (!env.SIGNING_PRIVATE_KEY) return jsonResponse({ error: "SIGNING_PRIVATE_KEY 未配置" }, 503);
    const incoming = (await request.json()) as { announcements?: unknown[]; failureHints?: unknown[] };
    const prev = await env.DB.prepare("SELECT version FROM manifest_config WHERE id = 1").first<{ version: number }>();
    const nextVersion = (prev?.version ?? 0) + 1;
    const now = Math.floor(Date.now() / 1000);
    const payloadObj = {
      version: nextVersion,
      generatedAt: now,
      announcements: Array.isArray(incoming.announcements) ? incoming.announcements : [],
      failureHints: Array.isArray(incoming.failureHints) ? incoming.failureHints : [],
    };
    const payloadJson = JSON.stringify(payloadObj);
    const signed = await signManifestPayload(payloadJson, env.SIGNING_PRIVATE_KEY, env.SIGNING_KEY_ID || "2026-k1");
    const envelopeJson = JSON.stringify(signed);
    const etag = `"v${nextVersion}-${(await sha256Hex(envelopeJson)).slice(0, 12)}"`;

    await env.DB.prepare(
      `INSERT INTO manifest_config (id, version, payload_json, signed_envelope_json, etag, updated_at)
       VALUES (1, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         version = excluded.version,
         payload_json = excluded.payload_json,
         signed_envelope_json = excluded.signed_envelope_json,
         etag = excluded.etag,
         updated_at = excluded.updated_at`
    )
      .bind(nextVersion, payloadJson, envelopeJson, etag, now)
      .run();

    await caches.default.delete(new Request(new URL("/v1/manifest.json", url.origin).toString(), { method: "GET" }));
    return jsonResponse({ version: nextVersion, etag, payload: payloadObj });
  }

  if (sub === "stats" && request.method === "GET") {
    const since = Math.floor(Date.now() / 1000) - 7 * 86400;
    const [totals, diagCnt, topErrors, topExits] = await Promise.all([
      env.DB.prepare(
        `SELECT COUNT(DISTINCT install_id) AS uniq,
                COUNT(DISTINCT CASE WHEN setup_complete = 1 THEN install_id END) AS done
         FROM telemetry_events WHERE created_at >= ?`
      ).bind(since).first<{ uniq: number; done: number }>(),
      env.DB.prepare("SELECT COUNT(*) AS cnt FROM diag_snapshots WHERE created_at >= ?")
        .bind(since).first<{ cnt: number }>(),
      env.DB.prepare(
        `SELECT error_code AS label, COUNT(*) AS count FROM telemetry_events
         WHERE created_at >= ? AND error_code IS NOT NULL AND error_code != ''
         GROUP BY error_code ORDER BY count DESC LIMIT 8`
      ).bind(since).all<{ label: string; count: number }>(),
      env.DB.prepare(
        `SELECT last_exit_reason AS label, COUNT(*) AS count FROM telemetry_events
         WHERE created_at >= ? AND last_exit_reason IS NOT NULL AND last_exit_reason != ''
         GROUP BY last_exit_reason ORDER BY count DESC LIMIT 8`
      ).bind(since).all<{ label: string; count: number }>(),
    ]);
    return jsonResponse({
      uniqueInstalls: totals?.uniq ?? 0,
      completedInstalls: totals?.done ?? 0,
      diagCount: diagCnt?.cnt ?? 0,
      topErrors: topErrors.results ?? [],
      topExitReasons: topExits.results ?? [],
    });
  }

  return jsonResponse({ error: "Unknown admin endpoint" }, 404);
}

async function checkRateLimit(
  request: Request,
  env: Env,
  scope: string,
  limit: number,
  windowSeconds: number
): Promise<boolean> {
  const ip = request.headers.get("CF-Connecting-IP") || "local";
  const ipHash = (await sha256Hex(`${env.IP_HASH_SALT || "maidroid-salt"}:${ip}`)).slice(0, 24);
  const now = Math.floor(Date.now() / 1000);
  const key = `${scope}:${ipHash}`;
  const existing = await env.DB.prepare("SELECT count, reset_at FROM rate_limits WHERE bucket_key = ?")
    .bind(key)
    .first<{ count: number; reset_at: number }>();

  if (!existing || existing.reset_at <= now) {
    await env.DB.prepare(
      `INSERT INTO rate_limits (bucket_key, count, reset_at) VALUES (?, 1, ?)
       ON CONFLICT(bucket_key) DO UPDATE SET count = 1, reset_at = excluded.reset_at`
    )
      .bind(key, now + windowSeconds)
      .run();
    return true;
  }
  if (existing.count >= limit) return false;
  await env.DB.prepare("UPDATE rate_limits SET count = count + 1 WHERE bucket_key = ?").bind(key).run();
  return true;
}

async function gunzipLimitedText(gzipBytes: Uint8Array, maxBytes: number): Promise<string> {
  const stream = new Blob([gzipBytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new Error("Decompressed payload exceeds limit");
    }
    chunks.push(value);
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(merged);
}

function safeStr(val: unknown, maxLen: number): string | null {
  if (typeof val !== "string") return null;
  const trimmed = val.trim();
  return trimmed ? trimmed.slice(0, maxLen) : null;
}

function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, If-None-Match",
  };
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...corsHeaders() },
  });
}
