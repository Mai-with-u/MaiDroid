// 解锁后才下发的控制台主体（标记 + 脚本）。
// 触发：登录闸门用管理员口令请求 GET /admin/api/console 成功后，把这里的标记挂进 #app，再把脚本作为内联 script 执行。
// 顺序：校验口令 -> 下发控制台 -> 拉取总览 -> 按需拉取报错 / 求助码 / 清单。
// 原因：口令通过前页面上只有登录框，看不到任何控制台结构、接口路径和操作入口。
// 注意：这里是 TS 模板字符串，脚本里不写反斜杠和美元加花括号，换行一律用 NL 常量。

import { ADMIN_MANIFEST_EDITOR_SCRIPT, ADMIN_MANIFEST_MARKUP } from "./adminManifestEditor.ts";
import { ADMIN_MANIFEST_PREVIEW_SCRIPT } from "./adminManifestPreview.ts";
import { ADMIN_TEAM_MARKUP, ADMIN_TEAM_SCRIPT } from "./adminTeam.ts";

export const ADMIN_CONSOLE_MARKUP = `
<div class="wrap">
  <header class="hd">
    <div class="brand"><b>MAI<em>DROID</em></b><span>运维控制台</span></div>
    <nav id="nav">
      <a data-act="view" data-v="overview" class="on"><i>01</i>总览</a>
      <a data-act="view" data-v="errors" data-min="support"><i>02</i>报错<span class="cnt" id="navErrCnt" hidden></span></a>
      <a data-act="view" data-v="diag" data-min="support"><i>03</i>求助码</a>
      <a data-act="view" data-v="manifest" data-min="maintainer"><i>04</i>清单</a>
      <a data-act="view" data-v="team" data-min="owner"><i>05</i>成员</a>
      <a data-act="view" data-v="audit" data-min="owner"><i>06</i>审计</a>
    </nav>
    <div class="hd-r">
      <div class="upd"><i></i><span id="clock">--:--:--</span><span id="syncAt">同步中</span></div>
      <div class="who" id="who"></div>
      <button class="btn" data-act="theme" id="themeBtn">日间</button>
      <button class="btn" data-act="pwd" id="pwdBtn">改口令</button>
      <button class="btn" data-act="lock">退出</button>
    </div>
  </header>

  <section class="view on" id="v-overview">
    <div class="deck">
      <div class="k"><div class="dl"><i></i>24H 活跃设备</div><div class="dv" id="kActive">0</div><div class="rd">近 24 小时 · 去重安装</div></div>
      <div class="k"><div class="dl">7 日活跃</div><div class="dv" id="k7d">0</div><div class="rd" id="k30d">30 日 0</div></div>
      <div class="k"><div class="dl">引导完成率</div><div class="dv" id="kDone">0</div><div class="rd" id="kDoneRd">-</div></div>
      <div class="k"><div class="dl">待处理报错</div><div class="dv" id="kOpen">0</div><div class="rd" id="kOpenRd">正式版</div></div>
      <div class="k"><div class="dl">7 日求助码</div><div class="dv" id="kDiag">0</div><div class="rd">用户主动生成</div></div>
      <div class="sys" id="sys">正在接入遥测链路...</div>
    </div>
    <div class="row">
      <div class="panel f8" data-busy="overview">
        <div class="ph"><h2>30 日健康条带</h2>
          <div class="lg"><span><i style="background:var(--teal)"></i>健康</span><span><i style="background:var(--mustard)"></i>报错 5%+</span><span><i style="background:var(--orange)"></i>报错 20%+</span></div>
          <span class="sp mono soft" id="healthSum"></span></div>
        <div class="hb" id="hb"></div>
        <div class="xl" id="hbx"></div>
      </div>
      <div class="panel f4"><div class="ph"><h2>组件状态</h2><span class="sp mono soft">LIVE</span></div><div id="comps"></div></div>
    </div>
    <div class="row">
      <div class="panel f5">
        <div class="ph"><h2>设备构成</h2>
          <div class="sp seg" id="shareSeg">
            <button class="on" data-act="share" data-v="manufacturers">厂商</button><button data-act="share" data-v="qqClients">协议端</button><button data-act="share" data-v="sdks">系统</button>
          </div></div>
        <div class="osbar" id="osbar"></div><div id="oslist"></div>
      </div>
      <div class="panel f7"><div class="ph"><h2>版本分布</h2><span class="sp mono soft">近 7 日 · 去重设备</span></div><div id="verlist"></div></div>
    </div>
    <div class="row">
      <div class="panel f7"><div class="ph"><h2>7×24 活跃热力</h2><span class="sp mono soft">近 28 日心跳 · 北京时间</span></div><div class="heat" id="heat"></div></div>
      <div class="panel f5"><div class="ph"><h2>引导漏斗</h2><span class="sp mono soft">7D</span></div><div class="fun" id="fun"></div><div id="oobeList" style="margin-top:12px"></div></div>
    </div>
    <div class="row">
      <div class="panel f6"><div class="ph"><h2>Top 错误码</h2><span class="sp mono soft">遥测心跳 · 7D</span></div><div id="topErr"></div></div>
      <div class="panel f6"><div class="ph"><h2>上次退出原因</h2><span class="sp mono soft">遥测心跳 · 7D</span></div><div id="topExit"></div></div>
    </div>
  </section>

  <section class="view" id="v-errors">
    <div class="panel"><div class="ph"><h2>自动报错聚合</h2><span class="sp mono soft">按指纹聚类 · release 与 debug 物理隔离</span></div><div class="chips" id="errChips"></div></div>
    <div class="panel" data-busy="errors">
      <div class="ph">
        <div class="seg" id="segStatus"><button class="on" data-act="errStatus" data-v="open">待处理</button><button data-act="errStatus" data-v="resolved">已修复</button><button data-act="errStatus" data-v="ignored">已忽略</button><button data-act="errStatus" data-v="all">全部</button></div>
        <div class="seg" id="segCat"><button class="on" data-act="errCat" data-v="all">全部分类</button><button data-act="errCat" data-v="crash">闪退</button><button data-act="errCat" data-v="install">安装/容器</button><button data-act="errCat" data-v="maibot_boot">MaiBot 启动</button></div>
        <label class="chk"><input type="checkbox" id="errDebug" data-act="errReload" /> 显示 debug 测试数据</label>
        <span class="sp toolbar"><button class="btn sm" data-act="errReload">刷新</button><button class="btn sm danger" data-act="errClearDebug" data-min="owner">清空 debug</button></span>
      </div>
      <div id="errList"></div>
    </div>
    <div id="errDetail"></div>
  </section>

  <section class="view" id="v-diag">
    <div class="panel">
      <div class="ph"><h2>故障求助码</h2><span class="sp mono soft">用户在 App 内生成 · 快照保留 7 日</span></div>
      <div class="codebar"><span class="mono" style="color:var(--hot);font-size:34px;font-weight:600">#</span><input id="diagCode" maxlength="8" placeholder="M8K-29F" autocomplete="off" spellcheck="false" /><button class="btn pri" data-act="diagLookup">查询快照</button></div>
    </div>
    <div id="diagResult"></div>
  </section>

  <!--MANIFEST-->
  <!--TEAM-->
</div>
<div class="modal" id="modal" hidden><div class="mbox" id="mbox"></div></div>`;

export const ADMIN_CONSOLE_SCRIPT = `
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const NL = String.fromCharCode(10);
  const MD = window.MD;
  const PALETTE = ["var(--orange)", "var(--teal)", "var(--mustard)", "var(--brown)", "var(--olive)", "var(--plum)", "var(--mute)", "var(--soft)"];
  const WEEK = ["一", "二", "三", "四", "五", "六", "日"];
  const st = { view: "overview", ov: null, share: "manufacturers", errStatus: "open", errCat: "all", activeKey: "", detail: null, reply: "", errLoaded: false };
  // 角色逐级包含；前端按角色隐藏入口只是体验，越权请求服务端一律 403
  const RANK = { viewer: 1, support: 2, maintainer: 3, owner: 4 };
  const ROLE_NAME = { viewer: "观察者", support: "客服", maintainer: "维护者", owner: "所有者" };
  function can(role) { return (RANK[MD.me.role] || 0) >= RANK[role]; }
  function applyRoles(root) {
    (root || document).querySelectorAll("[data-min]").forEach((el) => { el.hidden = !can(el.dataset.min); });
  }
  // 给“成员 / 审计”分页脚本共用的工具与分页钩子（函数声明会提升，这里可以直接引用）
  const MDC = (window.MDC = { views: {}, api, esc, fmt, fmtTime, ago, toast, busy, can, openModal, closeModal, ROLE_NAME });

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  function fmt(n) { return Number(n || 0).toLocaleString("en-US"); }
  function pct(a, b) { return b > 0 ? Math.round((a / b) * 1000) / 10 : 0; }
  function fmtTime(sec) { return sec ? new Date(sec * 1000).toLocaleString("zh-CN", { hour12: false }) : "-"; }
  function hhmm(d) { return d.toLocaleTimeString("zh-CN", { hour12: false }); }
  function ago(sec) {
    if (!sec) return "从未";
    const d = Math.max(0, Math.floor(Date.now() / 1000) - sec);
    if (d < 60) return d + " 秒前";
    if (d < 3600) return Math.floor(d / 60) + " 分钟前";
    if (d < 86400) return Math.floor(d / 3600) + " 小时前";
    return Math.floor(d / 86400) + " 天前";
  }
  function toast(msg, isErr) {
    const el = document.createElement("div");
    if (isErr) el.className = "err";
    el.textContent = (isErr ? "✕ " : "✓ ") + msg;
    $("toast").appendChild(el);
    setTimeout(() => { el.style.transition = "opacity .4s"; el.style.opacity = "0"; setTimeout(() => el.remove(), 400); }, 3200);
  }
  function busy(key, on) {
    document.querySelectorAll('[data-busy="' + key + '"]').forEach((p) => p.classList.toggle("busy", on));
  }

  // 所有接口统一带口令；口令被轮换或失效时直接退回登录闸门
  async function api(path, opts) {
    opts = opts || {};
    const headers = { Authorization: "Bearer " + MD.token };
    if (opts.body !== undefined) headers["Content-Type"] = "application/json";
    const r = await fetch(path, { method: opts.method || "GET", headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined });
    let d = {};
    try { d = await r.json(); } catch (e) { d = {}; }
    if (r.status === 401) { MD.lock("口令已失效，请重新解锁"); throw new Error("unauthorized"); }
    if (!r.ok) throw new Error(d.error || ("HTTP " + r.status));
    return d;
  }

  // 数字从旧值滚动到新值，仪表舱刷新时有“跳表”效果
  function roll(el, to, suffix) {
    const from = Number(el.dataset.v || 0);
    el.dataset.v = String(to);
    const t0 = performance.now();
    const dur = 1100;
    const isFloat = !Number.isInteger(to);
    function step(t) {
      const p = Math.min(1, (t - t0) / dur);
      const e = 1 - Math.pow(1 - p, 4);
      const v = from + (to - from) * e;
      el.innerHTML = (isFloat ? v.toFixed(1) : fmt(Math.round(v))) + (suffix || "");
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  // ---------- 01 总览 ----------
  async function loadOverview() {
    busy("overview", true);
    try {
      st.ov = await api("/admin/api/overview");
      renderOverview();
      $("syncAt").textContent = "已同步 " + hhmm(new Date()).slice(0, 5);
    } catch (e) {
      if (e.message !== "unauthorized") toast("总览加载失败：" + e.message, true);
    } finally { busy("overview", false); }
  }

  function renderOverview() {
    const o = st.ov;
    const k = o.kpi;
    roll($("kActive"), k.active24h);
    roll($("k7d"), k.installs7d);
    $("k30d").textContent = "30 日 " + fmt(k.installs30d);
    roll($("kDone"), pct(k.completed7d, k.installs7d), "<small>%</small>");
    $("kDoneRd").textContent = fmt(k.completed7d) + " / " + fmt(k.installs7d) + " 台完成";
    roll($("kOpen"), k.openIssues);
    $("kOpen").className = "dv " + (k.openIssues > 0 ? "hot" : "ok");
    $("kOpenRd").textContent = k.openIssues > 0 ? "影响 " + fmt(k.openInstalls) + " 台设备" : "正式版零待处理";
    roll($("kDiag"), k.diag7d);
    const nav = $("navErrCnt");
    nav.hidden = !(k.openIssues > 0 && can("support"));
    nav.textContent = String(k.openIssues);

    const c = o.components;
    const ok = k.openIssues === 0;
    $("sys").innerHTML =
      '<span class="' + (ok ? "ok" : "bad") + '">● ' + (ok ? "全部系统运行正常" : k.openIssues + " 个正式版问题待处理") + "</span>" +
      "<span>清单 <b>v" + esc(c.manifest.version) + "</b></span>" +
      "<span>公告 <b>" + c.manifest.announcements + "</b> · 热规则 <b>" + c.manifest.failureHints + "</b></span>" +
      "<span>最近心跳 <b>" + ago(c.telemetry.lastAt) + "</b></span>" +
      "<span>快照生成 <b>" + hhmm(new Date(o.generatedAt * 1000)) + "</b></span>";

    renderHealth(o.daily);
    renderComps(c);
    renderShare();
    renderBars("verlist", o.shares.versions, "t", (l) => "v" + l);
    renderHeat(o.heatmap);
    renderFunnel(k, o.shares.oobeSteps);
    renderBars("topErr", o.topErrors, "", null);
    renderBars("topExit", o.topExitReasons, "t", null);
  }

  function renderHealth(daily) {
    const max = Math.max(1, ...daily.map((d) => d.installs));
    let sum = 0;
    $("hb").innerHTML = daily.map((d, i) => {
      sum += d.installs;
      const cls = d.installs === 0 ? "none" : d.errRate >= 0.2 ? "bad" : d.errRate >= 0.05 ? "warn" : "";
      const h = d.installs === 0 ? 2 : Math.max(5, Math.round((d.installs / max) * 100));
      const tip = d.day + "|活跃设备 " + d.installs + "|报错设备 " + d.errInstalls + "（" + (d.errRate * 100).toFixed(1) + "%）|心跳 " + d.events + " 次";
      return '<div class="b ' + cls + '" data-tip="' + esc(tip) + '"><span style="height:' + h + "%;animation-delay:" + i * 22 + 'ms"></span></div>';
    }).join("");
    $("hbx").innerHTML = "<span>" + esc(daily[0].day) + "</span><span>" + esc(daily[15].day) + "</span><span>今天</span>";
    $("healthSum").textContent = "日均 " + (sum / daily.length).toFixed(1) + " 台";
  }

  function renderComps(c) {
    const label = { up: "在线", idle: "静默", none: "无数据" };
    const rows = [
      ["签名清单下发", c.manifest.state, "ECDSA P-256 · v" + c.manifest.version, c.manifest.updatedAt ? "发布于 " + ago(c.manifest.updatedAt) : "尚未发布"],
      ["匿名遥测接收", c.telemetry.state, "6 小时内有心跳视为在线", "最近 " + ago(c.telemetry.lastAt)],
      ["自动报错接收", c.errorReport.state, "3 日内有新报错视为在线", "最近 " + ago(c.errorReport.lastAt)],
      ["求助码快照通道", c.diag.state, "Gzip 脱敏快照 · 7 日过期", "最近 " + ago(c.diag.lastAt)],
    ];
    $("comps").innerHTML = rows.map((r) =>
      '<div class="comp"><span class="dot ' + r[1] + '"></span><div><div class="n">' + esc(r[0]) + '</div><div class="d">' + esc(r[2]) + "</div></div>" +
      '<div class="s">' + esc(label[r[1]] || r[1]) + "<br>" + esc(r[3]) + "</div></div>"
    ).join("");
  }

  function shareLabel(key, l) {
    if (key === "sdks") return "Android API " + l;
    return l;
  }
  function renderShare() {
    const arr = (st.ov && st.ov.shares[st.share]) || [];
    const total = arr.reduce((a, b) => a + b.count, 0);
    if (!total) {
      $("osbar").innerHTML = '<span style="flex:1;background:var(--track)"></span>';
      $("oslist").innerHTML = '<div class="empty">近 7 日暂无数据</div>';
      return;
    }
    $("osbar").innerHTML = arr.map((x, i) =>
      '<span style="flex:' + x.count + ";background:" + PALETTE[i % PALETTE.length] + ";animation-delay:" + i * 60 + 'ms" data-tip="' + esc(shareLabel(st.share, x.label) + "|" + x.count + " 台 · " + pct(x.count, total) + "%") + '"></span>'
    ).join("");
    $("oslist").innerHTML = arr.map((x, i) =>
      '<div class="lrow"><i style="background:' + PALETTE[i % PALETTE.length] + '"></i><span class="n">' + esc(shareLabel(st.share, x.label)) +
      '</span><span class="c">' + fmt(x.count) + '</span><span class="p">' + pct(x.count, total).toFixed(1) + "%</span></div>"
    ).join("");
  }

  function renderBars(id, arr, cls, labelFn) {
    arr = arr || [];
    if (!arr.length) { $(id).innerHTML = '<div class="empty">近 7 日暂无数据</div>'; return; }
    const max = Math.max(...arr.map((x) => x.count));
    $(id).innerHTML = arr.map((x, i) =>
      '<div class="mrow ' + cls + '"><span class="n" title="' + esc(x.label) + '">' + esc(labelFn ? labelFn(x.label) : x.label) +
      '</span><div class="tr"><span style="width:' + Math.max(2, (x.count / max) * 100) + "%;animation-delay:" + i * 50 + 'ms"></span></div><span class="p">' + fmt(x.count) + "</span></div>"
    ).join("");
  }

  function renderHeat(m) {
    const max = Math.max(1, ...m.flat());
    let html = "";
    for (let r = 0; r < 7; r++) {
      html += '<span class="y">' + WEEK[r] + "</span>";
      for (let h = 0; h < 24; h++) {
        const v = m[r][h];
        const op = v === 0 ? 0.07 : 0.2 + 0.8 * (v / max);
        html += '<span class="c" style="opacity:' + op.toFixed(2) + '" data-tip="' + esc("周" + WEEK[r] + " " + String(h).padStart(2, "0") + ":00|心跳 " + v + " 次") + '"></span>';
      }
    }
    html += "<span></span>";
    for (let h = 0; h < 24; h++) html += '<span class="x">' + (h % 6 === 0 ? String(h).padStart(2, "0") : "") + "</span>";
    $("heat").innerHTML = html;
  }

  function renderFunnel(k, steps) {
    const base = k.installs7d;
    const rows = [
      ["活跃装机", base, "var(--ink)"],
      ["完成引导", k.completed7d, "var(--teal)"],
      ["24H 仍在线", k.active24h, "var(--orange)"],
    ];
    $("fun").innerHTML = rows.map((r, i) =>
      '<div class="f"><span>' + r[0] + '</span><div class="tr"><span style="width:' + (base ? Math.max(1, pct(r[1], base)) : 0) + "%;background:" + r[2] + ";animation-delay:" + i * 120 + 'ms"></span></div>' +
      '<span class="p"><b>' + (base ? pct(r[1], base).toFixed(0) : 0) + "%</b><small>" + fmt(r[1]) + "</small></span></div>"
    ).join("");
    const box = $("oobeList");
    if (steps && steps.length) {
      box.innerHTML = '<div class="soft" style="font-size:12.5px;margin-bottom:4px">引导步骤分布（设备最后所在步骤）</div>';
      const holder = document.createElement("div");
      holder.id = "oobeBars";
      box.appendChild(holder);
      renderBars("oobeBars", steps, "", null);
    } else box.innerHTML = "";
  }

  // ---------- 02 报错 ----------
  function catTag(cat) {
    if (cat === "crash") return '<span class="tag crash">App 闪退</span>';
    if (cat === "maibot_boot") return '<span class="tag boot">MaiBot 启动</span>';
    return '<span class="tag install">安装 / 容器</span>';
  }
  function statusTag(s) {
    if (s === "resolved") return '<span class="tag ok">已修复</span>';
    if (s === "ignored") return '<span class="tag">已忽略</span>';
    return '<span class="tag warn">待处理</span>';
  }

  async function loadErrors() {
    busy("errors", true);
    try {
      const qs = new URLSearchParams({ status: st.errStatus, category: st.errCat, includeDebug: $("errDebug").checked ? "1" : "0" });
      const d = await api("/admin/api/errors?" + qs.toString());
      st.errLoaded = true;
      const sm = d.summary || {};
      const chips = [
        ["正式版待处理", sm.openCount, "var(--orange)"],
        ["已修复", sm.resolvedCount, "var(--olive)"],
        ["已忽略", sm.ignoredCount, "var(--mute)"],
        ["debug 测试", sm.debugCount, "var(--plum)"],
      ];
      $("errChips").innerHTML = chips.map((c) =>
        '<div class="chip" style="border-top-color:' + c[2] + '"><div class="l">' + c[0] + '</div><div class="v">' + fmt(c[1]) + "</div></div>"
      ).join("");
      const list = d.issues || [];
      if (!list.length) { $("errList").innerHTML = '<div class="empty">// 当前筛选条件下没有报错，世界和平</div>'; return; }
      $("errList").innerHTML = list.map((it, i) => {
        const key = it.build_type + ":" + it.fingerprint;
        const dbg = it.build_type !== "release" ? '<span class="tag debug">' + esc(it.build_type.toUpperCase()) + "</span>" : "";
        return '<div class="cr' + (i === 0 ? " top" : "") + (st.activeKey === key ? " on" : "") + '" data-act="errOpen" data-bt="' + esc(it.build_type) + '" data-fp="' + esc(it.fingerprint) + '">' +
          '<div class="rk">' + String(i + 1).padStart(2, "0") + "</div>" +
          '<div style="min-width:0"><div class="tags">' + catTag(it.category) + dbg + '<span class="tag">' + esc(it.error_code) + "</span>" + statusTag(it.status) + "</div>" +
          '<div class="t">' + esc(it.title) + "</div>" +
          '<div class="m">v' + esc(it.first_version) + " → v" + esc(it.last_version) + " · 最近 " + ago(it.last_seen_at) + " · " + esc(it.fingerprint.slice(0, 12)) + "</div></div>" +
          '<div class="num"><b>' + fmt(it.affected_installs) + "</b><small>台设备 · " + fmt(it.total_count) + " 次</small></div></div>";
      }).join("");
    } catch (e) {
      if (e.message !== "unauthorized") toast("报错列表加载失败：" + e.message, true);
    } finally { busy("errors", false); }
  }

  async function openError(bt, fp) {
    const key = bt + ":" + fp;
    if (st.activeKey === key) { st.activeKey = ""; st.detail = null; $("errDetail").innerHTML = ""; markActive(); return; }
    st.activeKey = key;
    markActive();
    $("errDetail").innerHTML = '<div class="panel busy"><div class="empty">正在解码报错样本...</div></div>';
    try {
      const d = await api("/admin/api/errors/" + encodeURIComponent(bt) + "/" + encodeURIComponent(fp));
      st.detail = d;
      renderDetail(bt, fp, d);
      $("errDetail").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (e) {
      if (e.message !== "unauthorized") $("errDetail").innerHTML = '<div class="panel"><span class="tag critical">' + esc(e.message) + "</span></div>";
    }
  }
  function markActive() {
    document.querySelectorAll("#errList .cr").forEach((el) => el.classList.toggle("on", el.dataset.bt + ":" + el.dataset.fp === st.activeKey));
  }

  function renderDetail(bt, fp, d) {
    const it = d.issue;
    const dist = d.distribution || {};
    const distText = (arr) => (arr || []).map((x) => x.label + "×" + x.count).join(" / ") || "无";
    const attrs = ' data-bt="' + esc(bt) + '" data-fp="' + esc(fp) + '"';
    const samples = (d.samples || []).map((s, idx) => {
      let extra = {};
      try { extra = JSON.parse(s.extra_json || "{}"); } catch (e) { extra = {}; }
      const parts = [];
      if (extra.oobeStep) parts.push("OOBE <b>" + esc(extra.oobeStep) + "</b>");
      if (extra.activeQqClient) parts.push("协议端 <b>" + esc(extra.activeQqClient) + "</b>");
      if (extra.availableStorageMb != null) parts.push("存储 <b>" + esc(extra.availableStorageMb) + "MB</b>");
      if (extra.availableMemMb != null) parts.push("内存 <b>" + esc(extra.availableMemMb) + "MB</b>");
      if (extra.lastExitReason) parts.push("退出 <b>" + esc(extra.lastExitReason) + "</b>");
      return '<div class="sample"><div class="kv"><span>样本 <b>#' + (idx + 1) + "</b></span><span>" + fmtTime(s.created_at) + "</span><span>v<b>" + esc(s.app_version) + "</b> (" + esc(s.version_code) + ")</span><span><b>" + esc(s.manufacturer) + " " + esc(s.model) + "</b></span><span>API <b>" + esc(s.sdk_int) + "</b></span>" + parts.map((p) => "<span>" + p + "</span>").join("") + "</div>" +
        '<div style="margin-top:8px;font-weight:500">' + esc(s.message) + "</div>" +
        '<pre class="term">' + esc(s.context_text || "（无脱敏上下文）") + "</pre></div>";
    }).join("");
    $("errDetail").innerHTML =
      '<div class="panel"><div class="ph"><div><div class="tags" style="display:flex;gap:6px;margin-bottom:6px">' + catTag(it.category) + statusTag(it.status) + '<span class="tag">' + esc(it.error_code) + "</span></div><h2>" + esc(it.title) + "</h2></div>" +
      '<span class="sp toolbar"' + (can("maintainer") ? "" : " hidden") + ">" +
      '<button class="btn sm pri" data-act="errPatch" data-st="resolved"' + attrs + ">标记已修复</button>" +
      '<button class="btn sm" data-act="errPatch" data-st="ignored"' + attrs + ">忽略</button>" +
      '<button class="btn sm" data-act="errPatch" data-st="open"' + attrs + ">重新打开</button>" +
      '<button class="btn sm" data-act="draftHint">生成热规则草稿</button></span></div>' +
      '<div class="badge"><span>版本</span><span>' + esc(distText(dist.versions)) + "</span></div>" +
      '<div class="badge o"><span>厂商</span><span>' + esc(distText(dist.manufacturers)) + "</span></div>" +
      '<div class="badge y"><span>系统</span><span>' + esc(distText(dist.sdks)) + "</span></div>" +
      '<div class="kv" style="margin-top:6px"><span>首次 <b>' + fmtTime(it.first_seen_at) + "</b></span><span>最近 <b>" + fmtTime(it.last_seen_at) + "</b></span><span>累计 <b>" + fmt(it.total_count) + " 次</b></span><span>影响 <b>" + fmt(it.affected_installs) + " 台</b></span><span>指纹 <b>" + esc(it.fingerprint) + "</b></span></div>" +
      samples + "</div>";
  }

  async function patchError(bt, fp, status) {
    try {
      await api("/admin/api/errors/" + encodeURIComponent(bt) + "/" + encodeURIComponent(fp), { method: "PATCH", body: { status } });
      toast("状态已更新为 " + ({ resolved: "已修复", ignored: "已忽略", open: "待处理" }[status] || status));
      st.activeKey = ""; st.detail = null; $("errDetail").innerHTML = "";
      loadErrors(); loadOverview();
    } catch (e) { if (e.message !== "unauthorized") toast("更新失败：" + e.message, true); }
  }

  async function clearDebug() {
    if (!confirm("确定清空全部测试包（debug）自动报错数据吗？正式版（release）记录不受影响。")) return;
    try {
      const d = await api("/admin/api/errors/debug", { method: "DELETE" });
      toast("已清空 " + d.deletedIssues + " 个 debug 问题、" + d.deletedSamples + " 条样本");
      st.activeKey = ""; st.detail = null; $("errDetail").innerHTML = "";
      loadErrors();
    } catch (e) { if (e.message !== "unauthorized") toast("清理失败：" + e.message, true); }
  }

  // ---------- 03 求助码 ----------
  async function lookupDiag() {
    const code = $("diagCode").value.trim();
    if (!code) { toast("先输入 6 位求助码", true); return; }
    $("diagResult").innerHTML = '<div class="panel busy"><div class="empty">正在解压脱敏快照...</div></div>';
    try {
      const d = await api("/admin/api/diag/" + encodeURIComponent(code));
      st.reply = d.advice.replyText || "";
      const snap = d.snapshot || {};
      const findings = (d.advice.findings || []).map((f) =>
        '<div class="lrow"><span class="tag ' + esc(f.level) + '">' + esc(f.title) + '</span><span class="n" style="white-space:normal;font-weight:400">' + esc(f.step) + "</span></div>"
      ).join("") || '<div class="empty">未发现明显权限或空间异常</div>';
      $("diagResult").innerHTML =
        '<div class="row"><div class="panel f7"><div class="ph"><h2>自动诊断结论</h2><span class="sp mono soft">#' + esc(d.code) + "</span></div>" +
        '<div class="kv" style="margin-bottom:10px"><span>提交 <b>' + fmtTime(d.createdAt) + "</b></span><span>版本 <b>" + esc(d.appVersion) + "</b></span><span>设备 <b>" + esc(d.device) + "</b></span><span>API <b>" + esc(d.sdkInt) + "</b></span><span>错误码 <b>" + esc(d.errorCode || "无") + "</b></span></div>" + findings + "</div>" +
        '<div class="panel f5"><div class="ph"><h2>回复话术</h2><span class="sp"><button class="btn sm pri" data-act="copyReply">一键复制</button></span></div><pre class="term">' + esc(st.reply) + "</pre></div></div>" +
        '<div class="panel"><div class="ph"><h2>原始现场</h2><span class="sp mono soft">已在设备端脱敏</span></div>' +
        '<details open><summary>脱敏诊断报告</summary><pre class="term">' + esc(snap.reportText || "") + "</pre></details>" +
        '<details><summary>终端输出尾部</summary><pre class="term">' + esc(snap.terminalTail || "（无）") + "</pre></details>" +
        '<details><summary>MaiBot 日志尾部 / 闪退栈</summary><pre class="term">' + esc((snap.maiBotLogTail || "") + NL + NL + (snap.latestCrash || "")) + "</pre></details></div>";
    } catch (e) {
      if (e.message !== "unauthorized") $("diagResult").innerHTML = '<div class="panel"><span class="tag critical">' + esc(e.message) + "</span></div>";
    }
  }

  // 04 清单：可视化编辑器在 adminManifestEditor 模块里，这里只负责把报错样本交给它生成热规则草稿
  async function draftHint() {
    if (!st.detail || !st.detail.issue || !MDC.mfDraftHint) return;
    switchView("manifest");
    await MDC.mfDraftHint(st.detail.issue, st.detail.samples);
    toast("已生成热规则草稿，补充原因与建议后发布");
  }

  // ---------- 外壳：导航、主题、时钟、提示框 ----------
  function switchView(v) {
    const tab = document.querySelector('#nav a[data-v="' + v + '"]');
    if (!tab || tab.hidden) return;
    st.view = v;
    document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("on", a.dataset.v === v));
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("on", s.id === "v-" + v));
    if (v === "errors" && !st.errLoaded) loadErrors();
    if (v === "diag") setTimeout(() => $("diagCode").focus(), 50);
    if (MDC.views[v]) MDC.views[v]();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  // ---------- 账号：身份徽标与改口令 ----------
  function renderWho() {
    const me = MD.me;
    $("who").innerHTML = '<b>' + esc(me.displayName) + '</b><span class="tag role-' + esc(me.role) + '">' + esc(ROLE_NAME[me.role]) + "</span>";
    $("pwdBtn").hidden = !!me.root;
  }
  function openModal(html, dismissible) {
    $("mbox").innerHTML = html;
    $("modal").hidden = false;
    $("modal").dataset.dismiss = dismissible ? "1" : "0";
    const first = $("mbox").querySelector("input");
    if (first) setTimeout(() => first.focus(), 50);
  }
  function closeModal() { $("modal").hidden = true; $("mbox").innerHTML = ""; }
  // 首次登录强制改口令：弹窗不可关闭，改完刷新页面重新拉取控制台
  function openPwdModal(forced) {
    openModal(
      '<div class="ph"><h2>' + (forced ? "首次登录 · 设置你的口令" : "修改口令") + "</h2></div>" +
      '<div class="soft" style="margin-bottom:14px">' + (forced ? "当前用的是一次性初始口令，换成你自己的口令（至少 10 位）后才能使用控制台。" : "改完后你在其他设备上的登录会被强制退出。") + "</div>" +
      '<label class="fld"><span>当前口令</span><input type="password" id="pwOld" autocomplete="current-password" /></label>' +
      '<label class="fld"><span>新口令</span><input type="password" id="pwNew" autocomplete="new-password" /></label>' +
      '<label class="fld"><span>再输一次</span><input type="password" id="pwNew2" autocomplete="new-password" /></label>' +
      '<div class="fmsg" id="pwMsg"></div>' +
      '<div class="toolbar"><span class="sp"></span>' + (forced ? '<button class="btn" data-act="lock">退出</button>' : '<button class="btn" data-act="modalClose">取消</button>') +
      '<button class="btn pri" data-act="pwdSave" data-forced="' + (forced ? "1" : "0") + '">保存</button></div>',
      !forced
    );
  }
  async function savePwd(forced) {
    const oldPw = $("pwOld").value;
    const np = $("pwNew").value;
    if (np.length < 10) { $("pwMsg").textContent = "新口令至少 10 位"; return; }
    if (np !== $("pwNew2").value) { $("pwMsg").textContent = "两次输入的新口令不一致"; return; }
    try {
      await api("/admin/api/password", { method: "POST", body: { oldPassword: oldPw, newPassword: np } });
      toast("口令已更新");
      if (forced) { location.reload(); return; }
      closeModal();
    } catch (e) { if (e.message !== "unauthorized") $("pwMsg").textContent = e.message; }
  }
  function segOn(segId, v) {
    $(segId).querySelectorAll("button").forEach((b) => b.classList.toggle("on", b.dataset.v === v));
  }
  function syncThemeBtn() {
    $("themeBtn").textContent = document.body.classList.contains("night") ? "日间" : "夜间";
  }

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el || !$("app").contains(el)) return;
    const a = el.dataset.act;
    if (a === "view") switchView(el.dataset.v);
    else if (a === "theme") {
      const next = document.body.classList.contains("night") ? "day" : "night";
      document.body.className = next;
      localStorage.setItem("md_theme", next);
      syncThemeBtn();
    } else if (a === "lock") MD.lock("已退出登录");
    else if (a === "pwd") openPwdModal(false);
    else if (a === "pwdSave") savePwd(el.dataset.forced === "1");
    else if (a === "modalClose") closeModal();
    else if (a === "share") { st.share = el.dataset.v; segOn("shareSeg", st.share); renderShare(); }
    else if (a === "errStatus") { st.errStatus = el.dataset.v; segOn("segStatus", st.errStatus); loadErrors(); }
    else if (a === "errCat") { st.errCat = el.dataset.v; segOn("segCat", st.errCat); loadErrors(); }
    else if (a === "errReload") { if (el.tagName !== "INPUT") loadErrors(); }
    else if (a === "errClearDebug") clearDebug();
    else if (a === "errOpen") openError(el.dataset.bt, el.dataset.fp);
    else if (a === "errPatch") patchError(el.dataset.bt, el.dataset.fp, el.dataset.st);
    else if (a === "draftHint") draftHint();
    else if (a === "diagLookup") lookupDiag();
    else if (a === "copyReply") navigator.clipboard.writeText(st.reply).then(() => toast("回复话术已复制"), () => toast("复制失败，请手动选择", true));
  });
  $("errDebug").addEventListener("change", loadErrors);
  $("diagCode").addEventListener("keydown", (e) => { if (e.key === "Enter") lookupDiag(); });

  // 悬浮提示：任何带 data-tip 的元素，第一段加粗，其余按竖线分行
  const vt = $("vt");
  document.addEventListener("mousemove", (e) => {
    const t = e.target.closest && e.target.closest("[data-tip]");
    if (!t) { vt.style.display = "none"; return; }
    const parts = t.dataset.tip.split("|");
    vt.innerHTML = parts.map((p, i) => (i === 0 ? "<b>" + esc(p) + "</b>" : esc(p))).join("<br>");
    vt.style.display = "block";
    const w = vt.offsetWidth;
    const x = e.clientX + 16 + w > window.innerWidth ? e.clientX - w - 16 : e.clientX + 16;
    vt.style.left = x + "px";
    vt.style.top = e.clientY + 16 + "px";
  });
  // 鼠标不动只滚动页面时，提示框会钉在别的面板上，滚动即收起
  window.addEventListener("scroll", () => { vt.style.display = "none"; }, { passive: true });

  setInterval(() => { $("clock").textContent = hhmm(new Date()); }, 1000);
  $("clock").textContent = hhmm(new Date());
  // 停留在总览页且窗口可见时每 60 秒自动刷新，后台标签页不白白消耗 D1 读额度
  setInterval(() => { if (st.view === "overview" && document.visibilityState === "visible" && !MD.me.mustChange) loadOverview(); }, 60000);
  // 点弹窗外的遮罩可关闭（强制改口令的弹窗除外）
  $("modal").addEventListener("click", (e) => { if (e.target === $("modal") && $("modal").dataset.dismiss === "1") closeModal(); });
  $("modal").addEventListener("keydown", (e) => { if (e.key === "Enter" && $("pwNew2")) savePwd($("modal").dataset.dismiss !== "1"); });
  syncThemeBtn();
  renderWho();
  applyRoles();
  if (MD.me.mustChange) openPwdModal(true);
  else loadOverview();
})();
`;

// 按角色拼装控制台：清单编辑器（预览 + 表单）只发给维护者及以上，成员与审计分页只发给所有者
export function buildConsoleBundle(roles: { maintainer: boolean; owner: boolean }) {
  return {
    markup: ADMIN_CONSOLE_MARKUP
      .replace("<!--MANIFEST-->", roles.maintainer ? ADMIN_MANIFEST_MARKUP : "")
      .replace("<!--TEAM-->", roles.owner ? ADMIN_TEAM_MARKUP : ""),
    script:
      ADMIN_CONSOLE_SCRIPT +
      (roles.maintainer ? ADMIN_MANIFEST_PREVIEW_SCRIPT + ADMIN_MANIFEST_EDITOR_SCRIPT : "") +
      (roles.owner ? ADMIN_TEAM_SCRIPT : ""),
  };
}