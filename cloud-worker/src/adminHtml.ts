// 管理台登录闸门页：GET /admin 只返回这一页，口令校验通过前页面上只有登录框。
// 触发：管理员打开 /admin。
// 顺序：输入口令 -> 带口令请求 /admin/api/console -> 通过后播放启动日志 -> 挂载控制台标记并执行控制台脚本。
// 失败：口令错误时登录框抖动并提示，连续失败会被服务端按 IP 熔断 15 分钟；口令只存在当前标签页的 sessionStorage。

import { ADMIN_CSS } from "./adminTheme.ts";

export const ADMIN_GATE_SCRIPT = `
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  document.body.className = localStorage.getItem("md_theme") === "day" ? "day" : "night";

  // 退出或会话失效时：通知服务端作废会话令牌，再带着原因刷新回闸门
  window.MD = {
    token: "",
    me: null,
    lock(msg) {
      const tok = sessionStorage.getItem("md_session");
      sessionStorage.removeItem("md_session");
      if (msg) sessionStorage.setItem("md_gate_msg", msg);
      const done = () => location.reload();
      if (!tok) { done(); return; }
      fetch("/admin/api/logout", { method: "POST", headers: { Authorization: "Bearer " + tok }, keepalive: true }).then(done, done);
    },
  };
  sessionStorage.removeItem("md_admin_token");
  const pending = sessionStorage.getItem("md_gate_msg");
  if (pending) { $("gmsg").textContent = "// " + pending; sessionStorage.removeItem("md_gate_msg"); }
  $("guser").value = localStorage.getItem("md_last_user") || "";

  const tick = () => { const c = $("gclock"); if (c) c.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false }); };
  tick();
  const clockTimer = setInterval(tick, 1000);

  function logLine(html) {
    const d = document.createElement("div");
    d.innerHTML = html;
    $("glog").appendChild(d);
  }
  function fail(msg) {
    $("gbtn").disabled = false;
    $("gmsg").textContent = "✕ " + msg;
    $("gstate").textContent = "LOCKED · 等待管理员口令";
    const box = $("gbox");
    box.classList.remove("shake");
    void box.offsetWidth;
    box.classList.add("shake");
    $("gpass").select();
  }

  function failMsg(status, d) {
    if (status === 429) return "尝试次数过多，链路已熔断 15 分钟";
    if (status === 401) return d.error || "账号或口令错误 · ACCESS DENIED";
    return d.error || ("HTTP " + status);
  }

  // 第一步：账号 + 口令换会话令牌（已有会话则跳过）；第二步：凭会话令牌拉取控制台本体
  async function unlock(username, password, savedToken) {
    $("gbtn").disabled = true;
    $("gmsg").textContent = "";
    $("glog").innerHTML = "";
    $("gstate").textContent = "HANDSHAKE · 正在校验身份";
    logLine("&gt; 建立安全会话 ...");
    let token = savedToken;
    try {
      if (!token) {
        const lr = await fetch("/admin/api/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username, password }),
          cache: "no-store",
        });
        const ld = await lr.json().catch(() => ({}));
        if (!lr.ok) { $("glog").innerHTML = ""; fail(failMsg(lr.status, ld)); return; }
        token = ld.token;
        localStorage.setItem("md_last_user", username);
      }
      const r = await fetch("/admin/api/console", { headers: { Authorization: "Bearer " + token }, cache: "no-store" });
      const d = await r.json().catch(() => ({}));
      if (!r.ok) {
        sessionStorage.removeItem("md_session");
        $("glog").innerHTML = "";
        fail(savedToken && r.status === 401 ? "会话已过期，请重新登录" : failMsg(r.status, d));
        return;
      }
      sessionStorage.setItem("md_session", token);
      window.MD.token = token;
      window.MD.me = d.me;
      $("gstate").textContent = "ACCESS GRANTED · 欢迎回来，" + d.me.displayName;
      for (const s of ["身份校验 · " + d.me.roleLabel, "下发控制台模块", "挂载遥测通道", "点亮仪表舱"]) {
        logLine("&gt; " + s + " ... <b>OK</b>");
        await sleep(savedToken ? 70 : 160);
      }
      $("gate").classList.add("fade-out");
      await sleep(460);
      clearInterval(clockTimer);
      $("gate").remove();
      $("app").innerHTML = d.markup;
      const s = document.createElement("script");
      s.textContent = d.script;
      document.body.appendChild(s);
    } catch (e) {
      fail("网络异常：" + e.message);
    }
  }

  $("gbox").addEventListener("submit", (e) => {
    e.preventDefault();
    const p = $("gpass").value;
    if (!p) { fail("请输入口令"); return; }
    unlock($("guser").value.trim().toLowerCase(), p, "");
  });
  const saved = sessionStorage.getItem("md_session");
  if (saved) unlock("", "", saved);
  else ($("guser").value ? $("gpass") : $("guser")).focus();
})();
`;

export const ADMIN_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<meta name="robots" content="noindex,nofollow" />
<title>MAIDROID // 运维控制台</title>
<style>${ADMIN_CSS}</style>
</head>
<body class="night">
<div class="stripes"></div>
<div id="gate" class="gate">
  <form class="gbox" id="gbox" autocomplete="off">
    <div class="sig"><i style="background:var(--orange)"></i><i style="background:var(--mustard)"></i><i style="background:var(--teal)"></i></div>
    <h1>MAI<em>DROID</em></h1>
    <div class="sub">远程运维 · 遥测控制台</div>
    <div class="lock"><i></i><span id="gstate">SECURE CHANNEL · 等待身份校验</span></div>
    <label class="gfield"><span>@</span><input id="guser" type="text" name="username" placeholder="账号（超级管理员留空）" autocomplete="username" spellcheck="false" autocapitalize="off" /></label>
    <label class="gfield"><span>&gt;</span><input id="gpass" type="password" placeholder="口令 / ADMIN_TOKEN" autocomplete="current-password" spellcheck="false" /><span class="cursor"></span></label>
    <button class="go" id="gbtn" type="submit">解 锁</button>
    <div class="msg" id="gmsg"></div>
    <div class="log" id="glog"></div>
    <div class="foot"><span>RESTRICTED AREA · 未授权访问将被熔断</span><span id="gclock"></span></div>
  </form>
</div>
<div id="app"></div>
<div class="toast" id="toast"></div>
<div class="vt" id="vt"></div>
<script>${ADMIN_GATE_SCRIPT}</script>
</body>
</html>`;

// 闸门页安全响应头：禁止被嵌入、禁止外链脚本与外发请求、不带来源、不被收录
export const ADMIN_PAGE_HEADERS: Record<string, string> = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store",
  "Content-Security-Policy":
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  "X-Frame-Options": "DENY",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Robots-Tag": "noindex, nofollow",
};
