// 内置单页管理台 HTML（零外部 CDN 依赖，输入 ADMIN_TOKEN 后可查求助码、发签名公告/热规则、看 7 日遥测漏斗）。

export const ADMIN_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>MaiDroid 远程运维与诊断台</title>
<style>
  :root { --bg:#0B0C0E; --card:#1A1B1F; --border:#262830; --accent:#E97F0F; --text:#FFF; --muted:#8E929B; --ok:#30D158; --warn:#E8A064; --danger:#FF453A; }
  * { box-sizing:border-box; }
  body { margin:0; padding:20px; background:var(--bg); color:var(--text); font:14px/1.6 system-ui,-apple-system,sans-serif; }
  .wrap { max-width:1040px; margin:0 auto; display:grid; gap:18px; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:16px; padding:18px; }
  h1 { margin:0; font-size:22px; } h2 { margin:0 0 12px; font-size:17px; }
  .row { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
  input, textarea { background:#0F1014; color:var(--text); border:1px solid var(--border); border-radius:10px; padding:9px 12px; font:inherit; }
  textarea { width:100%; min-height:220px; font-family:ui-monospace,Consolas,monospace; font-size:12.5px; }
  button { background:var(--accent); color:#fff; border:0; border-radius:10px; padding:9px 15px; font-weight:700; cursor:pointer; }
  button.sec { background:var(--border); }
  pre { background:#090A0C; padding:12px; border-radius:10px; overflow:auto; max-height:360px; font-size:12px; white-space:pre-wrap; word-break:break-word; }
  .tag { display:inline-block; padding:2px 8px; border-radius:6px; font-size:12px; background:var(--border); color:var(--muted); }
  .tag.critical { background:rgba(255,69,58,.18); color:var(--danger); }
  .tag.warn { background:rgba(232,160,100,.18); color:var(--warn); }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:12px; }
  .stat { background:#111216; padding:12px; border-radius:12px; }
  .stat b { font-size:22px; color:var(--accent); display:block; }
  .muted { color:var(--muted); font-size:12.5px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card row" style="justify-content:space-between">
    <div><h1>MaiDroid 远程运维与诊断台</h1><span class="muted">求助码秒查 · 签名公告与排障热规则下发 · 7 日匿名指标</span></div>
    <div class="row">
      <input id="token" type="password" placeholder="输入 ADMIN_TOKEN" style="width:220px" />
      <button class="sec" onclick="saveToken()">保存并加载</button>
    </div>
  </div>

  <div class="card">
    <h2>1. 故障求助码秒查</h2>
    <div class="row">
      <input id="diagCode" placeholder="输入 6 位求助码，如 #M8K-29F" style="width:260px" onkeydown="if(event.key==='Enter')lookupDiag()" />
      <button onclick="lookupDiag()">查询快照</button>
      <button class="sec" id="copyReplyBtn" style="display:none" onclick="copyReply()">一键复制给用户的回复话术</button>
    </div>
    <div id="diagResult" style="margin-top:12px"></div>
  </div>

  <div class="card">
    <h2>2. 远程公告与动态排障热规则（保存自动 ECDSA 签名生效）</h2>
    <div class="row" style="margin-bottom:10px">
      <button class="sec" onclick="insertAnnTpl()">+ 插入公告模板</button>
      <button class="sec" onclick="insertHintTpl()">+ 插入排障热规则模板</button>
      <button onclick="publishManifest()">签名并发布</button>
      <button class="sec" onclick="downloadSigned()">下载已签名 manifest.json（供 GitHub 兜底分支）</button>
      <span id="manifestStatus" class="muted"></span>
    </div>
    <textarea id="manifestEditor" spellcheck="false"></textarea>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:10px">
      <h2 style="margin:0">3. 近 7 日匿名运行大盘</h2>
      <button class="sec" onclick="loadStats()">刷新统计</button>
    </div>
    <div id="statsBox" class="muted">填入 ADMIN_TOKEN 后点击加载</div>
  </div>
</div>

<script>
const $ = (id) => document.getElementById(id);
$("token").value = sessionStorage.getItem("md_admin_token") || "";
let lastReply = "";

function authHeaders() {
  return { "Authorization": "Bearer " + $("token").value.trim(), "Content-Type": "application/json" };
}
function saveToken() {
  sessionStorage.setItem("md_admin_token", $("token").value.trim());
  loadManifest(); loadStats();
}
function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}

async function lookupDiag() {
  const code = $("diagCode").value.trim();
  if (!code) return;
  $("diagResult").innerHTML = '<span class="muted">正在读取解压快照...</span>';
  const r = await fetch("/admin/api/diag/" + encodeURIComponent(code), { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("diagResult").innerHTML = '<span class="tag critical">' + esc(d.error || r.statusText) + '</span>'; return; }
  lastReply = d.advice.replyText;
  $("copyReplyBtn").style.display = "inline-block";
  const findings = d.advice.findings.map(f => '<div style="margin:4px 0"><span class="tag ' + esc(f.level) + '">' + esc(f.title) + '</span> ' + esc(f.step) + '</div>').join("");
  const snap = d.snapshot || {};
  $("diagResult").innerHTML =
    '<div class="muted" style="margin-bottom:8px">提交时间：' + new Date(d.createdAt * 1000).toLocaleString() + ' · 版本：' + esc(d.appVersion) + ' · 设备：' + esc(d.device) + ' · 错误码：' + esc(d.errorCode || "无") + '</div>' +
    '<div><b>自动诊断结论：</b>' + (findings || '<span class="muted">未发现明显权限或空间异常</span>') + '</div>' +
    '<div style="margin-top:8px"><b>建议直接回复用户：</b><pre>' + esc(lastReply) + '</pre></div>' +
    '<details style="margin-top:8px"><summary>查看脱敏诊断报告正文</summary><pre>' + esc(snap.reportText || "") + '</pre></details>' +
    '<details style="margin-top:8px"><summary>查看终端输出尾部</summary><pre>' + esc(snap.terminalTail || "（无）") + '</pre></details>' +
    '<details style="margin-top:8px"><summary>查看 MaiBot 日志尾部 / 闪退栈</summary><pre>' + esc((snap.maiBotLogTail || "") + "\\n\\n" + (snap.latestCrash || "")) + '</pre></details>';
}
async function copyReply() {
  await navigator.clipboard.writeText(lastReply);
  alert("已复制回复话术");
}
async function loadManifest() {
  const r = await fetch("/admin/api/manifest", { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("manifestStatus").textContent = d.error || "鉴权失败"; return; }
  $("manifestEditor").value = JSON.stringify(d.payload, null, 2);
  $("manifestStatus").textContent = "当前版本 v" + d.version;
}
function insertAnnTpl() {
  try {
    const obj = JSON.parse($("manifestEditor").value || '{"announcements":[],"failureHints":[]}');
    obj.announcements = obj.announcements || [];
    obj.announcements.unshift({
      id: "ann-" + new Date().toISOString().slice(0, 10) + "-01",
      level: "info", title: "运行提醒标题", body: "这里填写面向用户的清晰提示说明。",
      actionLabel: null, actionTarget: null, notifySystemBar: false, dismissible: true,
      expiresAt: Math.floor(Date.now() / 1000) + 7 * 86400,
      filter: { minAppVersion: null, maxAppVersion: null, manufacturers: [], minSdkInt: null, activeQqClients: [], onlyDuringOobe: false }
    });
    $("manifestEditor").value = JSON.stringify(obj, null, 2);
  } catch (e) { alert("当前 JSON 格式有误：" + e.message); }
}
function insertHintTpl() {
  try {
    const obj = JSON.parse($("manifestEditor").value || '{"announcements":[],"failureHints":[]}');
    obj.failureHints = obj.failureHints || [];
    obj.failureHints.unshift({
      id: "hint-" + Date.now(), steps: [], containsAny: ["报错特征关键字"],
      title: "给用户看的原因标题", advice: "给用户看的分步解决办法。", action: null
    });
    $("manifestEditor").value = JSON.stringify(obj, null, 2);
  } catch (e) { alert("当前 JSON 格式有误：" + e.message); }
}
async function publishManifest() {
  let parsed;
  try { parsed = JSON.parse($("manifestEditor").value); } catch (e) { alert("JSON 语法错误：" + e.message); return; }
  const r = await fetch("/admin/api/manifest", { method: "PUT", headers: authHeaders(), body: JSON.stringify(parsed) });
  const d = await r.json();
  if (!r.ok) { alert("发布失败：" + (d.error || r.statusText)); return; }
  $("manifestEditor").value = JSON.stringify(d.payload, null, 2);
  $("manifestStatus").textContent = "已签名发布 v" + d.version;
}
function downloadSigned() { window.open("/v1/manifest.json", "_blank"); }
async function loadStats() {
  const r = await fetch("/admin/api/stats", { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("statsBox").textContent = d.error || "加载失败"; return; }
  const listHtml = (arr) => (arr || []).map(x => '<div>' + esc(x.label) + '：<b>' + x.count + '</b></div>').join("") || "暂无";
  $("statsBox").innerHTML =
    '<div class="grid">' +
      '<div class="stat">近7日独立设备<b>' + d.uniqueInstalls + '</b>已完成引导：' + d.completedInstalls + '</div>' +
      '<div class="stat">近7日求助码数<b>' + d.diagCount + '</b></div>' +
      '<div class="stat"><div><b>Top 错误码</b></div>' + listHtml(d.topErrors) + '</div>' +
      '<div class="stat"><div><b>上次退出原因分布</b></div>' + listHtml(d.topExitReasons) + '</div>' +
    '</div>';
}
if ($("token").value) { loadManifest(); loadStats(); }
</script>
</body>
</html>`;
