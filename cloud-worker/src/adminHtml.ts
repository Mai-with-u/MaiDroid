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

  // 锁定或口令失效时带着原因刷新回闸门
  window.MD = {
    token: "",
    lock(msg) {
      sessionStorage.removeItem("md_admin_token");
      if (msg) sessionStorage.setItem("md_gate_msg", msg);
      location.reload();
    },
  };
  const pending = sessionStorage.getItem("md_gate_msg");
  if (pending) { $("gmsg").textContent = "// " + pending; sessionStorage.removeItem("md_gate_msg"); }

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

  async function unlock(token, quick) {
    $("gbtn").disabled = true;
    $("gmsg").textContent = "";
    $("glog").innerHTML = "";
    $("gstate").textContent = "HANDSHAKE · 正在校验口令";
    logLine("&gt; 建立安全会话 ...");
    let r;
    let d = {};
    try {
      r = await fetch("/admin/api/console", { headers: { Authorization: "Bearer " + token }, cache: "no-store" });
      d = await r.json().catch(() => ({}));
    } catch (e) {
      fail("网络异常：" + e.message);
      return;
    }
    if (!r.ok) {
      sessionStorage.removeItem("md_admin_token");
      $("glog").innerHTML = "";
      fail(r.status === 429 ? "尝试次数过多，链路已熔断 15 分钟" : r.status === 401 ? "口令错误 · ACCESS DENIED" : (d.error || ("HTTP " + r.status)));
      return;
    }
    sessionStorage.setItem("md_admin_token", token);
    window.MD.token = token;
    $("gstate").textContent = "ACCESS GRANTED · 欢迎回来";
    for (const s of ["口令校验", "下发控制台模块", "挂载遥测通道", "点亮仪表舱"]) {
      logLine("&gt; " + s + " ... <b>OK</b>");
      await sleep(quick ? 70 : 160);
    }
    $("gate").classList.add("fade-out");
    await sleep(460);
    clearInterval(clockTimer);
    $("gate").remove();
    $("app").innerHTML = d.markup;
    const s = document.createElement("script");
    s.textContent = d.script;
    document.body.appendChild(s);
  }

  $("gbox").addEventListener("submit", (e) => {
    e.preventDefault();
    const t = $("gpass").value.trim();
    if (!t) { fail("请输入口令"); return; }
    unlock(t, false);
  });
  const saved = sessionStorage.getItem("md_admin_token");
  if (saved) unlock(saved, true);
  else $("gpass").focus();
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
    <div class="lock"><i></i><span id="gstate">SECURE CHANNEL · 等待管理员口令</span></div>
    <input type="text" name="username" value="admin" autocomplete="username" hidden />
    <label class="gfield"><span>&gt;</span><input id="gpass" type="password" placeholder="ADMIN_TOKEN" autocomplete="current-password" spellcheck="false" /><span class="cursor"></span></label>
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
