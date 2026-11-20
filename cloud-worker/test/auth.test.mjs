import assert from "node:assert/strict";
import test from "node:test";
import {
  generateInitialPassword,
  handleAccountRoute,
  handleLogin,
  hashPassword,
  requiredRoleFor,
  resolvePrincipal,
  verifyPassword,
} from "../src/adminAuth.ts";
import worker from "../src/index.ts";
import { createSqliteD1 } from "./sqliteD1.mjs";

const BASE = "https://ops.8u.nz/admin/api/";

function req(path, { method = "GET", token, body, ip = "1.1.1.1" } = {}) {
  const headers = { "CF-Connecting-IP": ip };
  if (token) headers.Authorization = "Bearer " + token;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return new Request(BASE + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
}

// 走一遍“解析身份 -> 账号路由”，模拟 index.ts 的调用顺序
async function call(env, path, opts) {
  const r = req(path, opts);
  const p = await resolvePrincipal(r, env);
  assert.ok(p, "expected authenticated principal for " + path);
  const resp = await handleAccountRoute(path.split("?")[0], r, env, p);
  return { status: resp.status, body: await resp.json() };
}

async function login(env, username, password, ip) {
  const resp = await handleLogin(req("login", { method: "POST", body: { username, password }, ip }), env);
  return { status: resp.status, body: await resp.json() };
}

test("PBKDF2 password hash round-trips and initial passwords avoid ambiguous chars", async () => {
  const h = await hashPassword("correct horse battery", 2000);
  assert.match(h, /^pbkdf2\$2000\$/);
  assert.equal(await verifyPassword("correct horse battery", h), true);
  assert.equal(await verifyPassword("correct horse batterY", h), false);
  assert.equal(await verifyPassword("x", "garbage"), false);
  const pw = generateInitialPassword();
  assert.match(pw, /^[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}$/);
  assert.equal(/[0O1lI]/.test(pw), false);
});

test("requiredRoleFor maps each admin endpoint to the minimum role and defaults to owner", () => {
  assert.equal(requiredRoleFor("overview", "GET"), "viewer");
  assert.equal(requiredRoleFor("console", "GET"), "viewer");
  assert.equal(requiredRoleFor("diag/M8K-29F", "GET"), "support");
  assert.equal(requiredRoleFor("errors", "GET"), "support");
  assert.equal(requiredRoleFor("errors/release/abcdef0123456789", "GET"), "support");
  assert.equal(requiredRoleFor("errors/release/abcdef0123456789", "PATCH"), "maintainer");
  assert.equal(requiredRoleFor("manifest", "GET"), "maintainer");
  assert.equal(requiredRoleFor("manifest", "PUT"), "maintainer");
  assert.equal(requiredRoleFor("errors/debug", "DELETE"), "owner");
  assert.equal(requiredRoleFor("users", "GET"), "owner");
  assert.equal(requiredRoleFor("audit", "GET"), "owner");
  assert.equal(requiredRoleFor("something-new", "GET"), "owner");
});

test("member lifecycle: root creates member, forced password change, owner protection, disable kills sessions", async () => {
  const env = { DB: createSqliteD1(), ADMIN_TOKEN: "root-secret", IP_HASH_SALT: "s" };

  // 1. 账号留空用 ADMIN_TOKEN 登录，换发会话令牌
  const rootLogin = await login(env, "", "root-secret");
  assert.equal(rootLogin.status, 200);
  assert.equal(rootLogin.body.me.root, true);
  const rootTok = rootLogin.body.token;
  assert.notEqual(rootTok, "root-secret");

  // 2. 超级管理员建一个客服和一个所有者
  const created = await call(env, "users", { method: "POST", token: rootTok, body: { username: "Helper.One", displayName: "客服一号", role: "support" } });
  assert.equal(created.status, 200);
  assert.equal(created.body.user.username, "helper.one");
  assert.equal(created.body.user.mustChange, true);
  const initial = created.body.initialPassword;
  const owner2 = await call(env, "users", { method: "POST", token: rootTok, body: { username: "owner2", role: "owner" } });
  const owner2Id = owner2.body.user.id;

  // 3. 成员用初始口令登录，必须先改口令；旧口令不对、新口令太短都拒绝
  const memberLogin = await login(env, "helper.one", initial);
  assert.equal(memberLogin.status, 200);
  assert.equal(memberLogin.body.me.mustChange, true);
  assert.equal(memberLogin.body.me.role, "support");
  const memTok = memberLogin.body.token;
  assert.equal((await call(env, "password", { method: "POST", token: memTok, body: { oldPassword: "nope", newPassword: "abcdefghijk" } })).status, 400);
  assert.equal((await call(env, "password", { method: "POST", token: memTok, body: { oldPassword: initial, newPassword: "short" } })).status, 400);
  const changed = await call(env, "password", { method: "POST", token: memTok, body: { oldPassword: initial, newPassword: "helper-new-pass-1" } });
  assert.equal(changed.status, 200);
  assert.equal((await call(env, "me", { token: memTok })).body.me.mustChange, false);
  assert.equal((await login(env, "helper.one", initial)).status, 401);

  // 4. 所有者账号只能由超级管理员授予或管理：用 owner2 的会话去改另一个所有者会被拒
  const o2Login = await login(env, "owner2", owner2.body.initialPassword);
  const o2Tok = o2Login.body.token;
  const promote = await call(env, "users", { method: "POST", token: o2Tok, body: { username: "sneaky", role: "owner" } });
  assert.equal(promote.status, 403);
  assert.equal((await call(env, `users/${owner2Id}`, { method: "PATCH", token: o2Tok, body: { disabled: true } })).status, 400);

  // 5. 停用成员后其会话立即失效，审计里留下全过程
  const memberId = created.body.user.id;
  const dis = await call(env, `users/${memberId}`, { method: "PATCH", token: rootTok, body: { disabled: true } });
  assert.equal(dis.body.user.disabled, true);
  assert.equal(await resolvePrincipal(req("me", { token: memTok }), env), null);
  assert.equal((await login(env, "helper.one", "helper-new-pass-1")).status, 401);

  // 6. 重置口令返回新的一次性口令并恢复强制改密
  await call(env, `users/${memberId}`, { method: "PATCH", token: rootTok, body: { disabled: false } });
  const reset = await call(env, `users/${memberId}`, { method: "PATCH", token: rootTok, body: { resetPassword: true } });
  assert.equal(reset.body.user.mustChange, true);
  assert.equal((await login(env, "helper.one", reset.body.initialPassword)).status, 200);

  const audit = await call(env, "audit", { token: rootTok });
  const actions = audit.body.entries.map((e) => e.action);
  for (const a of ["login", "user_create", "password_change", "user_update", "login_fail"]) assert.ok(actions.includes(a), "missing audit " + a);
  const filtered = await call(env, "audit?action=user_create", { token: rootTok });
  assert.equal(filtered.body.entries.length, 2);
  assert.ok(filtered.body.entries.every((e) => e.action === "user_create" && e.actor === "root"));

  // 7. 登出后令牌失效
  await call(env, "logout", { method: "POST", token: o2Tok });
  assert.equal(await resolvePrincipal(req("me", { token: o2Tok }), env), null);
});

test("login lockout blocks even correct credentials after 8 failures from the same IP", async () => {
  const env = { DB: createSqliteD1(), ADMIN_TOKEN: "root-secret", IP_HASH_SALT: "s" };
  for (let i = 0; i < 8; i++) assert.equal((await login(env, "", "wrong-" + i, "9.9.9.9")).status, 401);
  assert.equal((await login(env, "", "root-secret", "9.9.9.9")).status, 429);
  // 换个 IP 不受影响
  assert.equal((await login(env, "", "root-secret", "8.8.8.8")).status, 200);
});

test("worker enforces role ladder end to end: viewer < support < maintainer < owner", async () => {
  const env = { DB: createSqliteD1(), ADMIN_TOKEN: "root-secret", IP_HASH_SALT: "s" };
  const ctx = { waitUntil() {} };
  const fetchApi = async (path, opts) => {
    const resp = await worker.fetch(req(path, opts), env, ctx);
    return { status: resp.status, body: await resp.json() };
  };

  // 超级管理员直接带 ADMIN_TOKEN 建三个不同角色的成员，各自登录并改掉初始口令
  const tokens = {};
  for (const role of ["viewer", "support", "maintainer"]) {
    const c = await fetchApi("users", { method: "POST", token: "root-secret", body: { username: "m-" + role, role } });
    assert.equal(c.status, 200);
    const lg = await fetchApi("login", { method: "POST", body: { username: "m-" + role, password: c.body.initialPassword } });
    tokens[role] = lg.body.token;
    // 改口令之前什么都不让做，但能拿到控制台以弹出改口令窗口
    assert.equal((await fetchApi("overview", { token: tokens[role] })).body.code, "must_change");
    assert.equal((await fetchApi("console", { token: tokens[role] })).body.me.mustChange, true);
    const pw = await fetchApi("password", { method: "POST", token: tokens[role], body: { oldPassword: c.body.initialPassword, newPassword: "new-pass-" + role } });
    assert.equal(pw.status, 200);
  }

  const fp = "release/0123456789abcdef0123456789abcdef";
  const matrix = [
    // [接口, 方法, 观察者, 客服, 维护者]
    ["overview", "GET", 200, 200, 200],
    ["diag/M8K-29F", "GET", 403, 404, 404],
    ["errors", "GET", 403, 200, 200],
    ["errors/" + fp, "PATCH", 403, 403, 404],
    ["manifest", "GET", 403, 403, 200],
    ["errors/debug", "DELETE", 403, 403, 403],
    ["users", "GET", 403, 403, 403],
    ["audit", "GET", 403, 403, 403],
  ];
  for (const [path, method, ...expect] of matrix) {
    for (const [i, role] of ["viewer", "support", "maintainer"].entries()) {
      const body = method === "PATCH" ? { status: "resolved" } : undefined;
      const r = await fetchApi(path, { method, token: tokens[role], body });
      assert.equal(r.status, expect[i], `${role} ${method} ${path}`);
    }
  }

  // 客服查求助码（哪怕没查到）也会留下审计，所有者能看到是谁查的
  const audit = await fetchApi("audit?action=diag_lookup", { token: "root-secret" });
  assert.deepEqual(audit.body.entries.map((e) => e.actor).sort(), ["m-maintainer", "m-support"]);
  assert.equal((await fetchApi("errors/debug", { method: "DELETE", token: "root-secret" })).status, 200);
  assert.equal((await fetchApi("overview", { token: "bogus-token" })).status, 401);
});
