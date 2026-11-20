// 管理台多用户与权限分级：内置账号 + 会话令牌 + 四级角色 + 审计日志。
// 触发：任何 /admin/api/* 请求先经 resolvePrincipal 认出“是谁、什么角色”，再按 requiredRoleFor 判定能不能做。
// 角色从低到高逐级包含：观察者（只看总览）< 客服（查求助码、看报错）< 维护者（改报错状态、发布清单）< 所有者（成员管理、清空 debug、审计）。
// ADMIN_TOKEN 是超级管理员 / 应急入口，永远是所有者，且只有它能授予、修改所有者账号，防止成员之间互相夺权。

import { bytesToBase64, base64ToBytes, sha256Hex, timingSafeEqualText } from "./crypto.ts";

export type Role = "viewer" | "support" | "maintainer" | "owner";
export const ROLES: Role[] = ["viewer", "support", "maintainer", "owner"];
const ROLE_RANK: Record<Role, number> = { viewer: 1, support: 2, maintainer: 3, owner: 4 };
export const ROLE_LABEL: Record<Role, string> = { viewer: "观察者", support: "客服", maintainer: "维护者", owner: "所有者" };

// Workers 的 WebCrypto 对 PBKDF2 迭代次数上限是 100000；哈希串里带迭代次数，以后调整不影响旧账号
export const PBKDF2_ITERATIONS = 100000;
export const SESSION_TTL_SECONDS = 12 * 3600;
export const AUDIT_TTL_SECONDS = 90 * 86400;
const LOGIN_FAIL_LIMIT = 8;
const LOGIN_FAIL_WINDOW = 900;
const ROOT_USERNAME = "root";

export interface AuthEnv {
  DB: D1Database;
  ADMIN_TOKEN?: string;
  IP_HASH_SALT?: string;
}

export interface Principal {
  userId: number;
  username: string;
  displayName: string;
  role: Role;
  root: boolean;
  mustChange: boolean;
  sessionHash: string | null;
}

interface UserRow {
  id: number;
  username: string;
  display_name: string;
  role: string;
  password_hash: string;
  must_change: number;
  disabled: number;
  created_at: number;
  updated_at: number;
  last_login_at: number | null;
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

export function isRole(v: unknown): v is Role {
  return typeof v === "string" && (ROLES as string[]).includes(v);
}

export function hasRole(p: Principal, need: Role): boolean {
  return ROLE_RANK[p.role] >= ROLE_RANK[need];
}

// 每个管理接口需要的最低角色；没列出来的一律按所有者处理（默认拒绝）
export function requiredRoleFor(sub: string, method: string): Role {
  if (["console", "me", "password", "logout", "overview", "stats"].includes(sub)) return "viewer";
  if (sub.startsWith("diag/") && method === "GET") return "support";
  if (sub === "errors" && method === "GET") return "support";
  if (sub === "errors/debug") return "owner";
  if (sub.startsWith("errors/")) return method === "GET" ? "support" : "maintainer";
  if (sub === "manifest") return "maintainer";
  return "owner";
}

// ---------- 口令 ----------
async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256);
  return new Uint8Array(bits);
}

export async function hashPassword(password: string, iterations = PBKDF2_ITERATIONS): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(password, salt, iterations);
  return `pbkdf2$${iterations}$${bytesToBase64(salt)}$${bytesToBase64(hash)}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 1000 || iterations > PBKDF2_ITERATIONS) return false;
  const actual = await pbkdf2(password, base64ToBytes(parts[2]), iterations);
  const expected = base64ToBytes(parts[3]);
  if (actual.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expected[i];
  return diff === 0;
}

// 一次性初始口令：去掉 0/O/1/l/I 等易混字符，4×4 分组方便口头或私聊转交
const PASS_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
export function generateInitialPassword(): string {
  const rand = crypto.getRandomValues(new Uint8Array(16));
  let out = "";
  for (let i = 0; i < 16; i++) {
    if (i > 0 && i % 4 === 0) out += "-";
    out += PASS_ALPHABET[rand[i] % PASS_ALPHABET.length];
  }
  return out;
}

export function validateNewPassword(pw: unknown): string | null {
  if (typeof pw !== "string" || pw.length < 10) return "新口令至少 10 位";
  if (pw.length > 128) return "新口令不能超过 128 位";
  return null;
}

function newSessionToken(): string {
  return bytesToBase64(crypto.getRandomValues(new Uint8Array(32))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// 登录失败计数按加盐 IP 哈希分桶，先查再试，避免撞到上限后仍能用正确口令探测
async function failBucket(request: Request, env: AuthEnv): Promise<string> {
  const ip = request.headers.get("CF-Connecting-IP") || "local";
  return `login_fail:${(await sha256Hex(`${env.IP_HASH_SALT || "maidroid-salt"}:${ip}`)).slice(0, 24)}`;
}

async function isLoginLocked(env: AuthEnv, key: string, now: number): Promise<boolean> {
  const row = await env.DB.prepare("SELECT count, reset_at FROM rate_limits WHERE bucket_key = ?")
    .bind(key).first<{ count: number; reset_at: number }>();
  return !!row && row.reset_at > now && row.count >= LOGIN_FAIL_LIMIT;
}

async function recordLoginFail(env: AuthEnv, key: string, now: number): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO rate_limits (bucket_key, count, reset_at) VALUES (?, 1, ?)
     ON CONFLICT(bucket_key) DO UPDATE SET
       count = CASE WHEN rate_limits.reset_at <= ? THEN 1 ELSE rate_limits.count + 1 END,
       reset_at = CASE WHEN rate_limits.reset_at <= ? THEN excluded.reset_at ELSE rate_limits.reset_at END`
  ).bind(key, now + LOGIN_FAIL_WINDOW, now, now).run();
}

// ---------- 身份识别 ----------
function rootPrincipal(sessionHash: string | null): Principal {
  return { userId: 0, username: ROOT_USERNAME, displayName: "超级管理员", role: "owner", root: true, mustChange: false, sessionHash };
}

export async function resolvePrincipal(request: Request, env: AuthEnv): Promise<Principal | null> {
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return null;
  // 直接携带 ADMIN_TOKEN（脚本、应急）视为超级管理员
  if (env.ADMIN_TOKEN && (await timingSafeEqualText(token, env.ADMIN_TOKEN))) return rootPrincipal(null);

  const tokenHash = await sha256Hex(token);
  const now = Math.floor(Date.now() / 1000);
  const session = await env.DB.prepare("SELECT user_id, expires_at FROM admin_sessions WHERE token_hash = ?")
    .bind(tokenHash).first<{ user_id: number; expires_at: number }>();
  if (!session || session.expires_at <= now) return null;
  if (session.user_id === 0) return env.ADMIN_TOKEN ? rootPrincipal(tokenHash) : null;

  const user = await env.DB.prepare("SELECT * FROM admin_users WHERE id = ?").bind(session.user_id).first<UserRow>();
  if (!user || user.disabled || !isRole(user.role)) return null;
  return {
    userId: user.id,
    username: user.username,
    displayName: user.display_name,
    role: user.role,
    root: false,
    mustChange: user.must_change === 1,
    sessionHash: tokenHash,
  };
}

export function meOf(p: Principal) {
  return { userId: p.userId, username: p.username, displayName: p.displayName, role: p.role, roleLabel: ROLE_LABEL[p.role], root: p.root, mustChange: p.mustChange };
}

export async function writeAudit(db: D1Database, p: Principal | null, action: string, target?: string | null, detail?: unknown, actorOverride?: string): Promise<void> {
  const detailText = detail == null ? null : (typeof detail === "string" ? detail : JSON.stringify(detail)).slice(0, 500);
  await db.prepare("INSERT INTO admin_audit (created_at, actor, role, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(Math.floor(Date.now() / 1000), (actorOverride ?? p?.username ?? "-").slice(0, 40), p?.role ?? "-", action, target ? String(target).slice(0, 120) : null, detailText)
    .run();
}

// ---------- 登录 ----------
// 账号留空 = 用 ADMIN_TOKEN 登录超级管理员；登录成功一律换发会话令牌，原始口令不在浏览器里长期保存
export async function handleLogin(request: Request, env: AuthEnv): Promise<Response> {
  const now = Math.floor(Date.now() / 1000);
  const bucket = await failBucket(request, env);
  if (await isLoginLocked(env, bucket, now)) return json({ error: "尝试次数过多，链路已熔断 15 分钟" }, 429);

  const body = (await request.json().catch(() => ({}))) as { username?: unknown; password?: unknown };
  const username = typeof body.username === "string" ? body.username.trim().toLowerCase() : "";
  const password = typeof body.password === "string" ? body.password : "";
  if (!password) return json({ error: "请输入口令" }, 400);

  let principal: Principal | null = null;
  if (!username || username === ROOT_USERNAME) {
    if (env.ADMIN_TOKEN && (await timingSafeEqualText(password, env.ADMIN_TOKEN))) principal = rootPrincipal(null);
  } else {
    const user = await env.DB.prepare("SELECT * FROM admin_users WHERE username = ?").bind(username).first<UserRow>();
    // 账号不存在也照样跑一遍哈希，避免靠响应耗时猜出哪些账号存在
    const ok = await verifyPassword(password, user?.password_hash ?? "pbkdf2$100000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    if (user && ok && !user.disabled && isRole(user.role)) {
      principal = { userId: user.id, username: user.username, displayName: user.display_name, role: user.role, root: false, mustChange: user.must_change === 1, sessionHash: null };
    }
  }

  if (!principal) {
    await recordLoginFail(env, bucket, now);
    await writeAudit(env.DB, null, "login_fail", null, null, username || ROOT_USERNAME);
    return json({ error: "账号或口令错误 · ACCESS DENIED" }, 401);
  }

  const token = newSessionToken();
  principal.sessionHash = await sha256Hex(token);
  await env.DB.batch([
    env.DB.prepare("INSERT INTO admin_sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
      .bind(principal.sessionHash, principal.userId, now, now + SESSION_TTL_SECONDS),
    env.DB.prepare("UPDATE admin_users SET last_login_at = ? WHERE id = ?").bind(now, principal.userId),
    // 顺手清理过期会话与 90 天前的审计记录，不另起定时任务
    env.DB.prepare("DELETE FROM admin_sessions WHERE expires_at <= ?").bind(now),
    env.DB.prepare("DELETE FROM admin_audit WHERE created_at < ?").bind(now - AUDIT_TTL_SECONDS),
  ]);
  await writeAudit(env.DB, principal, "login");
  return json({ token, expiresAt: now + SESSION_TTL_SECONDS, me: meOf(principal) });
}

// ---------- 账号、成员与审计接口 ----------
function publicUser(u: UserRow) {
  return {
    id: u.id,
    username: u.username,
    displayName: u.display_name,
    role: u.role,
    mustChange: u.must_change === 1,
    disabled: u.disabled === 1,
    createdAt: u.created_at,
    lastLoginAt: u.last_login_at,
  };
}

const USERNAME_RE = /^[a-z0-9][a-z0-9_.-]{2,31}$/;

function cleanDisplayName(v: unknown, fallback: string): string {
  const s = typeof v === "string" ? v.trim().slice(0, 32) : "";
  return s || fallback;
}

// 返回 null 表示不是本模块负责的路由，交回 index.ts 继续匹配
export async function handleAccountRoute(sub: string, request: Request, env: AuthEnv, p: Principal): Promise<Response | null> {
  const method = request.method;
  const now = Math.floor(Date.now() / 1000);

  if (sub === "me" && method === "GET") return json({ me: meOf(p) });

  if (sub === "logout" && method === "POST") {
    if (p.sessionHash) await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash = ?").bind(p.sessionHash).run();
    await writeAudit(env.DB, p, "logout");
    return json({ ok: true });
  }

  // 自己改口令：首次登录强制走这里；改完踢掉自己的其他会话
  if (sub === "password" && method === "POST") {
    if (p.root) return json({ error: "超级管理员口令即 ADMIN_TOKEN，请用 wrangler secret put 轮换" }, 400);
    const body = (await request.json().catch(() => ({}))) as { oldPassword?: unknown; newPassword?: unknown };
    const user = await env.DB.prepare("SELECT * FROM admin_users WHERE id = ?").bind(p.userId).first<UserRow>();
    if (!user || typeof body.oldPassword !== "string" || !(await verifyPassword(body.oldPassword, user.password_hash))) {
      return json({ error: "当前口令不正确" }, 400);
    }
    const bad = validateNewPassword(body.newPassword);
    if (bad) return json({ error: bad }, 400);
    if (body.newPassword === body.oldPassword) return json({ error: "新口令不能与当前口令相同" }, 400);
    await env.DB.batch([
      env.DB.prepare("UPDATE admin_users SET password_hash = ?, must_change = 0, updated_at = ? WHERE id = ?")
        .bind(await hashPassword(body.newPassword as string), now, p.userId),
      env.DB.prepare("DELETE FROM admin_sessions WHERE user_id = ? AND token_hash != ?").bind(p.userId, p.sessionHash ?? ""),
    ]);
    await writeAudit(env.DB, p, "password_change");
    return json({ ok: true });
  }

  if (sub === "users" && method === "GET") {
    const res = await env.DB.prepare("SELECT * FROM admin_users ORDER BY id").all<UserRow>();
    return json({ users: (res.results ?? []).map(publicUser), roles: ROLES.map((r) => ({ role: r, label: ROLE_LABEL[r] })) });
  }

  if (sub === "users" && method === "POST") {
    const body = (await request.json().catch(() => ({}))) as { username?: unknown; displayName?: unknown; role?: unknown };
    const username = typeof body.username === "string" ? body.username.trim().toLowerCase() : "";
    if (!USERNAME_RE.test(username) || username === ROOT_USERNAME) return json({ error: "账号需 3~32 位小写字母、数字或 _ . -，且不能为 root" }, 400);
    if (!isRole(body.role)) return json({ error: "角色无效" }, 400);
    if (body.role === "owner" && !p.root) return json({ error: "所有者账号只能由超级管理员授予" }, 403);
    const exists = await env.DB.prepare("SELECT id FROM admin_users WHERE username = ?").bind(username).first();
    if (exists) return json({ error: "账号已存在" }, 409);
    const initialPassword = generateInitialPassword();
    await env.DB.prepare(
      `INSERT INTO admin_users (username, display_name, role, password_hash, must_change, disabled, created_at, updated_at)
       VALUES (?, ?, ?, ?, 1, 0, ?, ?)`
    ).bind(username, cleanDisplayName(body.displayName, username), body.role, await hashPassword(initialPassword), now, now).run();
    const user = await env.DB.prepare("SELECT * FROM admin_users WHERE username = ?").bind(username).first<UserRow>();
    await writeAudit(env.DB, p, "user_create", username, { role: body.role });
    return json({ user: user ? publicUser(user) : null, initialPassword });
  }

  if (sub.startsWith("users/") && (method === "PATCH" || method === "DELETE")) {
    const id = Number(sub.slice("users/".length));
    if (!Number.isInteger(id) || id <= 0) return json({ error: "成员 ID 无效" }, 400);
    if (id === p.userId) return json({ error: "不能在成员管理里修改自己，改口令请用右上角“改口令”" }, 400);
    const user = await env.DB.prepare("SELECT * FROM admin_users WHERE id = ?").bind(id).first<UserRow>();
    if (!user) return json({ error: "成员不存在" }, 404);
    if (user.role === "owner" && !p.root) return json({ error: "所有者账号只能由超级管理员管理" }, 403);

    if (method === "DELETE") {
      await env.DB.batch([
        env.DB.prepare("DELETE FROM admin_sessions WHERE user_id = ?").bind(id),
        env.DB.prepare("DELETE FROM admin_users WHERE id = ?").bind(id),
      ]);
      await writeAudit(env.DB, p, "user_delete", user.username);
      return json({ ok: true });
    }

    const body = (await request.json().catch(() => ({}))) as { role?: unknown; disabled?: unknown; displayName?: unknown; resetPassword?: unknown };
    const changes: Record<string, unknown> = {};
    let role = user.role;
    let disabled = user.disabled;
    let displayName = user.display_name;
    let passwordHash = user.password_hash;
    let mustChange = user.must_change;
    let initialPassword: string | null = null;
    let killSessions = false;

    if (body.role !== undefined) {
      if (!isRole(body.role)) return json({ error: "角色无效" }, 400);
      if (body.role === "owner" && !p.root) return json({ error: "所有者账号只能由超级管理员授予" }, 403);
      if (body.role !== role) { changes.role = body.role; role = body.role; killSessions = true; }
    }
    if (body.disabled !== undefined) {
      const next = body.disabled ? 1 : 0;
      if (next !== disabled) { changes.disabled = next === 1; disabled = next; if (next) killSessions = true; }
    }
    if (body.displayName !== undefined) {
      displayName = cleanDisplayName(body.displayName, user.username);
      if (displayName !== user.display_name) changes.displayName = displayName;
    }
    // 重置口令：生成新的一次性口令、强制下次登录改掉，并踢掉该成员所有在线会话
    if (body.resetPassword === true) {
      initialPassword = generateInitialPassword();
      passwordHash = await hashPassword(initialPassword);
      mustChange = 1;
      killSessions = true;
      changes.resetPassword = true;
    }

    const stmts = [
      env.DB.prepare(
        "UPDATE admin_users SET role = ?, disabled = ?, display_name = ?, password_hash = ?, must_change = ?, updated_at = ? WHERE id = ?"
      ).bind(role, disabled, displayName, passwordHash, mustChange, now, id),
    ];
    if (killSessions) stmts.push(env.DB.prepare("DELETE FROM admin_sessions WHERE user_id = ?").bind(id));
    await env.DB.batch(stmts);
    await writeAudit(env.DB, p, "user_update", user.username, changes);
    const fresh = await env.DB.prepare("SELECT * FROM admin_users WHERE id = ?").bind(id).first<UserRow>();
    return json({ user: fresh ? publicUser(fresh) : null, initialPassword });
  }

  if (sub === "audit" && method === "GET") {
    const url = new URL(request.url);
    const action = (url.searchParams.get("action") || "").trim();
    const actor = (url.searchParams.get("actor") || "").trim().toLowerCase();
    const conds: string[] = [];
    const binds: string[] = [];
    if (/^[a-z_]{2,32}$/.test(action)) { conds.push("action = ?"); binds.push(action); }
    if (actor) { conds.push("actor = ?"); binds.push(actor.slice(0, 40)); }
    const where = conds.length ? `WHERE ${conds.join(" AND ")}` : "";
    const res = await env.DB.prepare(
      `SELECT id, created_at, actor, role, action, target, detail FROM admin_audit ${where} ORDER BY created_at DESC, id DESC LIMIT 200`
    ).bind(...binds).all();
    return json({ entries: res.results ?? [] });
  }

  return null;
}
