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
  input, select, textarea { background:#0F1014; color:var(--text); border:1px solid var(--border); border-radius:10px; padding:9px 12px; font:inherit; }
  textarea { width:100%; min-height:220px; font-family:ui-monospace,Consolas,monospace; font-size:12.5px; }
  button { background:var(--accent); color:#fff; border:0; border-radius:10px; padding:9px 15px; font-weight:700; cursor:pointer; }
  button.sec { background:var(--border); }
  button.danger { background:rgba(255,69,58,.2); color:var(--danger); border:1px solid rgba(255,69,58,.35); }
  button.sm { padding:5px 10px; font-size:12px; border-radius:8px; }
  pre { background:#090A0C; padding:12px; border-radius:10px; overflow:auto; max-height:360px; font-size:12px; white-space:pre-wrap; word-break:break-word; }
  .tag { display:inline-block; padding:2px 8px; border-radius:6px; font-size:12px; background:var(--border); color:var(--muted); }
  .tag.critical { background:rgba(255,69,58,.18); color:var(--danger); }
  .tag.warn { background:rgba(232,160,100,.18); color:var(--warn); }
  .tag.ok { background:rgba(48,209,88,.18); color:var(--ok); }
  .tag.debug { background:rgba(130,170,255,.18); color:#82AAFF; }
  .issue-row { border:1px solid var(--border); background:#111216; border-radius:12px; padding:12px 14px; cursor:pointer; transition:border-color .15s; }
  .issue-row:hover, .issue-row.active { border-color:var(--accent); }
  .sample-card { border:1px solid var(--border); background:#0D0E12; border-radius:10px; padding:10px 12px; margin-top:10px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:12px; }
  .stat { background:#111216; padding:12px; border-radius:12px; }
  .stat b { font-size:22px; color:var(--accent); display:block; }
  .muted { color:var(--muted); font-size:12.5px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card row" style="justify-content:space-between">
    <div><h1>MaiDroid 远程运维与诊断台</h1><span class="muted">求助码秒查 · 自动报错指纹聚合 · 签名公告与排障热规则下发 · 7 日匿名指标</span></div>
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
    <div class="row" style="justify-content:space-between;margin-bottom:10px">
      <h2 style="margin:0">2. 自动报错聚合看板（按指纹聚类，默认隔离测试包）</h2>
      <span id="errSummary" class="muted">填入 ADMIN_TOKEN 后加载</span>
    </div>
    <div class="row" style="margin-bottom:12px">
      <select id="errStatus" onchange="loadErrors()">
        <option value="open">待处理</option>
        <option value="resolved">已修复</option>
        <option value="ignored">已忽略</option>
        <option value="all">全部状态</option>
      </select>
      <select id="errCategory" onchange="loadErrors()">
        <option value="all">全部分类</option>
        <option value="crash">App 闪退 / ANR</option>
        <option value="install">安装 / 容器 / 更新</option>
        <option value="maibot_boot">MaiBot 启动失败</option>
      </select>
      <label class="row" style="gap:6px;cursor:pointer">
        <input id="errIncludeDebug" type="checkbox" onchange="loadErrors()" />
        <span class="muted">显示 debug 测试数据</span>
      </label>
      <button class="sec sm" onclick="loadErrors()">刷新列表</button>
      <button class="danger sm" onclick="clearDebugErrors()">清空 debug 数据</button>
    </div>
    <div id="errList" style="display:grid;gap:10px"></div>
    <div id="errDetail" style="margin-top:14px"></div>
  </div>

  <div class="card" id="manifestCard">
    <h2>3. 远程公告与动态排障热规则（保存自动 ECDSA 签名生效）</h2>
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
      <h2 style="margin:0">4. 近 7 日匿名运行大盘</h2>
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
  loadErrors(); loadManifest(); loadStats();
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
function fmtTime(sec) {
  return sec ? new Date(sec * 1000).toLocaleString() : "-";
}
function catTag(cat) {
  if (cat === "crash") return '<span class="tag critical">App 闪退</span>';
  if (cat === "maibot_boot") return '<span class="tag warn">MaiBot 启动</span>';
  return '<span class="tag">安装/容器</span>';
}
function statusTag(st) {
  if (st === "resolved") return '<span class="tag ok">已修复</span>';
  if (st === "ignored") return '<span class="tag">已忽略</span>';
  return '<span class="tag warn">待处理</span>';
}

let activeErrKey = "";
let activeErrDetail = null;

async function loadErrors() {
  const qs = new URLSearchParams({
    status: $("errStatus").value,
    category: $("errCategory").value,
    includeDebug: $("errIncludeDebug").checked ? "1" : "0"
  });
  const r = await fetch("/admin/api/errors?" + qs.toString(), { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("errSummary").textContent = d.error || "加载失败"; return; }
  const sm = d.summary || {};
  $("errSummary").textContent = "正式版 待处理 " + (sm.openCount || 0) + " · 已修复 " + (sm.resolvedCount || 0) + " · 已忽略 " + (sm.ignoredCount || 0) + " · debug " + (sm.debugCount || 0);
  const issues = d.issues || [];
  if (issues.length === 0) {
    $("errList").innerHTML = '<div class="muted">当前筛选条件下暂无自动报错记录</div>';
    $("errDetail").innerHTML = "";
    return;
  }
  $("errList").innerHTML = issues.map((it) => {
    const key = it.build_type + ":" + it.fingerprint;
    const dbg = it.build_type !== "release" ? '<span class="tag debug">' + esc(it.build_type.toUpperCase()) + '</span> ' : "";
    return '<div class="issue-row' + (activeErrKey === key ? ' active' : '') + '" data-bt="' + esc(it.build_type) + '" data-fp="' + esc(it.fingerprint) + '" onclick="openErrorDetail(this.dataset.bt,this.dataset.fp)">' +
      '<div class="row" style="justify-content:space-between">' +
        '<div class="row" style="gap:6px">' + catTag(it.category) + dbg + '<span class="tag">' + esc(it.error_code) + '</span>' + statusTag(it.status) + ' <b>' + esc(it.title) + '</b></div>' +
        '<span class="muted">影响 ' + it.affected_installs + ' 台 · 共 ' + it.total_count + ' 次</span>' +
      '</div>' +
      '<div class="muted" style="margin-top:4px">版本：' + esc(it.first_version) + ' ~ ' + esc(it.last_version) + ' · 最近：' + fmtTime(it.last_seen_at) + ' · 指纹：' + esc(it.fingerprint.slice(0, 12)) + '</div>' +
    '</div>';
  }).join("");
}

async function openErrorDetail(buildType, fingerprint) {
  const key = buildType + ":" + fingerprint;
  if (activeErrKey === key && activeErrDetail) {
    activeErrKey = "";
    activeErrDetail = null;
    $("errDetail").innerHTML = "";
    loadErrors();
    return;
  }
  activeErrKey = key;
  $("errDetail").innerHTML = '<div class="muted">正在加载报错样本...</div>';
  const r = await fetch("/admin/api/errors/" + encodeURIComponent(buildType) + "/" + encodeURIComponent(fingerprint), { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("errDetail").innerHTML = '<span class="tag critical">' + esc(d.error || "加载失败") + '</span>'; return; }
  activeErrDetail = d;
  const it = d.issue;
  const dist = d.distribution || {};
  const distText = (arr) => (arr || []).map((x) => x.label + "×" + x.count).join(" / ") || "无";
  const samplesHtml = (d.samples || []).map((s, idx) => {
    let extra = {};
    try { extra = JSON.parse(s.extra_json || "{}"); } catch {}
    const extraParts = [];
    if (extra.oobeStep) extraParts.push("OOBE: " + extra.oobeStep);
    if (extra.activeQqClient) extraParts.push("QQ: " + extra.activeQqClient);
    if (extra.availableStorageMb != null) extraParts.push("可用存储: " + extra.availableStorageMb + "MB");
    if (extra.availableMemMb != null) extraParts.push("可用内存: " + extra.availableMemMb + "MB");
    if (extra.lastExitReason) extraParts.push("退出原因: " + extra.lastExitReason);
    return '<div class="sample-card">' +
      '<div class="muted">样本 #' + (idx + 1) + ' · ' + fmtTime(s.created_at) + ' · v' + esc(s.app_version) + ' (' + s.version_code + ') · ' + esc(s.manufacturer) + ' ' + esc(s.model) + ' (API ' + s.sdk_int + ')</div>' +
      (extraParts.length ? '<div class="muted">' + esc(extraParts.join(" · ")) + '</div>' : '') +
      '<div style="margin:4px 0"><b>摘要：</b>' + esc(s.message) + '</div>' +
      '<pre>' + esc(s.context_text || "（无脱敏上下文）") + '</pre>' +
    '</div>';
  }).join("");

  $("errDetail").innerHTML =
    '<div class="stat">' +
      '<div class="row" style="justify-content:space-between;margin-bottom:8px">' +
        '<div><b>' + esc(it.title) + '</b> <span class="muted">(' + esc(it.error_code) + ')</span></div>' +
        '<div class="row">' +
          '<button class="sm" data-bt="' + esc(buildType) + '" data-fp="' + esc(fingerprint) + '" data-st="resolved" onclick="patchErrorStatus(this.dataset.bt,this.dataset.fp,this.dataset.st)">标记已修复</button>' +
          '<button class="sec sm" data-bt="' + esc(buildType) + '" data-fp="' + esc(fingerprint) + '" data-st="ignored" onclick="patchErrorStatus(this.dataset.bt,this.dataset.fp,this.dataset.st)">忽略</button>' +
          '<button class="sec sm" data-bt="' + esc(buildType) + '" data-fp="' + esc(fingerprint) + '" data-st="open" onclick="patchErrorStatus(this.dataset.bt,this.dataset.fp,this.dataset.st)">重新打开</button>' +
          '<button class="sec sm" onclick="draftHintFromError()">一键生成排障热规则草稿</button>' +
        '</div>' +
      '</div>' +
      '<div class="muted">样本版本分布：' + esc(distText(dist.versions)) + ' ｜ 厂商：' + esc(distText(dist.manufacturers)) + ' ｜ 系统：' + esc(distText(dist.sdks)) + '</div>' +
      samplesHtml +
    '</div>';
  loadErrors();
}

async function patchErrorStatus(buildType, fingerprint, status) {
  const r = await fetch("/admin/api/errors/" + encodeURIComponent(buildType) + "/" + encodeURIComponent(fingerprint), {
    method: "PATCH",
    headers: authHeaders(),
    body: JSON.stringify({ status })
  });
  const d = await r.json();
  if (!r.ok) { alert("更新失败：" + (d.error || r.statusText)); return; }
  activeErrKey = "";
  activeErrDetail = null;
  $("errDetail").innerHTML = "";
  loadErrors();
  loadStats();
}

async function clearDebugErrors() {
  if (!confirm("确定清空全部测试包（debug）自动报错数据吗？不会影响正式版（release）记录。")) return;
  const r = await fetch("/admin/api/errors/debug", { method: "DELETE", headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { alert("清理失败：" + (d.error || r.statusText)); return; }
  activeErrKey = "";
  activeErrDetail = null;
  $("errDetail").innerHTML = "";
  loadErrors();
}

function draftHintFromError() {
  if (!activeErrDetail || !activeErrDetail.issue) return;
  try {
    const obj = JSON.parse($("manifestEditor").value || '{"announcements":[],"failureHints":[]}');
    obj.failureHints = obj.failureHints || [];
    const issue = activeErrDetail.issue;
    const stepCode = String(issue.error_code || "").split("/")[0];
    const sample = (activeErrDetail.samples || [])[0];
    const firstNonBlank = sample && sample.context_text
      ? sample.context_text.split("\\n").map((l) => l.trim()).filter(Boolean).slice(-1)[0]
      : "";
    const keyword = (firstNonBlank || issue.title || "报错特征关键字").slice(0, 60);
    obj.failureHints.unshift({
      id: "hint-" + Date.now(),
      steps: stepCode ? [stepCode] : [],
      containsAny: [keyword],
      title: "补充面向用户的排查说明：" + issue.title.slice(0, 40),
      advice: "请在此填写分步解决建议。",
      action: null
    });
    $("manifestEditor").value = JSON.stringify(obj, null, 2);
    $("manifestCard").scrollIntoView({ behavior: "smooth" });
  } catch (e) { alert("当前清单 JSON 格式有误：" + e.message); }
}

async function loadStats() {
  const r = await fetch("/admin/api/stats", { headers: authHeaders() });
  const d = await r.json();
  if (!r.ok) { $("statsBox").textContent = d.error || "加载失败"; return; }
  const listHtml = (arr) => (arr || []).map(x => '<div>' + esc(x.label) + '：<b>' + x.count + '</b></div>').join("") || "暂无";
  $("statsBox").innerHTML =
    '<div class="grid">' +
      '<div class="stat">近7日独立设备<b>' + d.uniqueInstalls + '</b>已完成引导：' + d.completedInstalls + '</div>' +
      '<div class="stat">近7日求助码数<b>' + d.diagCount + '</b>待处理正式版报错：' + (d.openReleaseIssues || 0) + '</div>' +
      '<div class="stat"><div><b>Top 错误码</b></div>' + listHtml(d.topErrors) + '</div>' +
      '<div class="stat"><div><b>上次退出原因分布</b></div>' + listHtml(d.topExitReasons) + '</div>' +
    '</div>';
}
if ($("token").value) { loadErrors(); loadManifest(); loadStats(); }
</script>
</body>
</html>`;
