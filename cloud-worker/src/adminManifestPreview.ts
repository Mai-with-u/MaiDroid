// 清单可视化编辑器的“预览与模拟匹配”部分：照着 App 端渲染与匹配规则复刻，保证所见即所得。
// - 渲染：公告横幅（设置页 / 首次引导页顶部）、blocker 强提醒弹窗、系统通知栏、排障热规则在失败提示里的样子；
//   配色取自 App 主题（页底 #0B0C0E、卡片 #1A1B1F、普通公告蓝点 #389BFF、警示橙点 #E8A064、品牌橙 #E97F0F）。
// - 匹配：RemoteManifest.kt 的 matches() / matchRemoteFailureHint()、RemoteActionTarget.kt 的动作白名单、VersionNumber 的版本比较。
// 本模块只产出 HTML 字符串、不碰 DOM，脚本可以直接在 Node 里执行做单测。
// 注意：这里是 TS 模板字符串，脚本里不写反斜杠和美元加花括号，正则里的点一律写成 [.]。

export const ADMIN_MANIFEST_PREVIEW_SCRIPT = `
(function () {
  "use strict";
  const C = window.MDC;
  const NL = String.fromCharCode(10);
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // ---------- 动作白名单：与 parseRemoteActionTarget 一致，只认固定几个应用内入口和 https 链接 ----------
  const ACTION_ALIAS = {
    "open:app_update": "app_update", openappupdate: "app_update",
    "open:maibot_update": "maibot_update", openmaibotupdate: "maibot_update",
    "open:cleanup": "cleanup", freeupstorage: "cleanup",
    "open:qq_group": "qq_group", openqqgroup: "qq_group",
    "open:permissions": "permissions", openpermissions: "permissions",
  };
  const DEFAULT_LABEL = { app_update: "检查应用更新", maibot_update: "打开 MaiBot 更新", cleanup: "清理空间", qq_group: "QQ 群反馈", permissions: "应用权限设置", url: "查看详情" };
  function parseAction(raw) {
    const t = String(raw == null ? "" : raw).trim();
    if (!t) return null;
    const low = t.toLowerCase();
    if (ACTION_ALIAS[low]) return { kind: ACTION_ALIAS[low] };
    let url = null;
    if (low.startsWith("url:https://")) url = t.slice(4).trim();
    else if (low.startsWith("https://")) url = t;
    if (url && url.length > "https://a.b".length) return { kind: "url", url };
    return null;
  }
  function actionText(label, raw) {
    const p = parseAction(raw);
    if (!p) return null;
    const l = label == null ? "" : String(label).trim();
    return l || DEFAULT_LABEL[p.kind];
  }

  // ---------- 版本比较：与 VersionNumber 一致（数字段逐位比，缺位按 0，同号时带预发布后缀的更旧） ----------
  function parseVersion(text) {
    let s = String(text == null ? "" : text).trim();
    if (s[0] === "v" || s[0] === "V") s = s.slice(1);
    s = s.split("+")[0];
    const dash = s.indexOf("-");
    const core = dash >= 0 ? s.slice(0, dash) : s;
    const pre = dash >= 0 ? s.slice(dash + 1) : null;
    if (!/^[0-9]+([.][0-9]+)*$/.test(core)) return null;
    if (pre !== null && !/^[0-9A-Za-z-]+([.][0-9A-Za-z-]+)*$/.test(pre)) return null;
    return { nums: core.split(".").map(Number), pre };
  }
  function cmpPre(a, b) {
    if (a === b) return 0;
    if (a === null) return 1;
    if (b === null) return -1;
    const ap = a.split("."), bp = b.split(".");
    for (let i = 0; i < Math.min(ap.length, bp.length); i++) {
      const an = /^[0-9]+$/.test(ap[i]) ? Number(ap[i]) : null;
      const bn = /^[0-9]+$/.test(bp[i]) ? Number(bp[i]) : null;
      let d;
      if (an !== null && bn !== null) d = an - bn;
      else if (an !== null) d = -1;
      else if (bn !== null) d = 1;
      else d = ap[i] < bp[i] ? -1 : ap[i] > bp[i] ? 1 : 0;
      if (d !== 0) return d < 0 ? -1 : 1;
    }
    return ap.length === bp.length ? 0 : ap.length < bp.length ? -1 : 1;
  }
  function cmpVersion(a, b) {
    const n = Math.max(a.nums.length, b.nums.length);
    for (let i = 0; i < n; i++) {
      const d = (a.nums[i] || 0) - (b.nums[i] || 0);
      if (d !== 0) return d < 0 ? -1 : 1;
    }
    return cmpPre(a.pre, b.pre);
  }

  // ---------- 公告是否命中某台设备：逐条对齐 RemoteAnnouncement.matches()，返回不命中的原因 ----------
  function annMissReason(ann, env) {
    const f = ann.filter || {};
    if (!String(ann.id || "").trim() || !String(ann.title || "").trim() || !String(ann.body || "").trim()) return "ID、标题或正文为空，客户端会直接丢弃";
    if (ann.expiresAt != null && env.now >= ann.expiresAt) return "已过期";
    if (f.onlyDuringOobe && env.setupComplete) return "只在首次引导期间展示，而这台设备已完成引导";
    if (f.minSdkInt != null && env.sdkInt < f.minSdkInt) return "系统版本低于 Android API " + f.minSdkInt;
    const brands = f.manufacturers || [];
    if (brands.length && !brands.some((b) => String(b).toLowerCase() === String(env.manufacturer || "").trim().toLowerCase())) return "品牌不在定向名单里";
    const qqs = f.activeQqClients || [];
    if (qqs.length) {
      const active = String(env.activeQqClient || "").trim().toLowerCase();
      if (!active || !qqs.some((q) => String(q).toLowerCase() === active)) return "当前协议端不在定向名单里";
    }
    const cur = parseVersion(env.appVersion);
    const min = f.minAppVersion && String(f.minAppVersion).trim() ? parseVersion(f.minAppVersion) : null;
    const max = f.maxAppVersion && String(f.maxAppVersion).trim() ? parseVersion(f.maxAppVersion) : null;
    if (min && (!cur || cmpVersion(cur, min) < 0)) return "App 版本低于 " + f.minAppVersion;
    if (max && (!cur || cmpVersion(cur, max) > 0)) return "App 版本高于 " + f.maxAppVersion;
    return null;
  }

  // ---------- 热规则匹配：对齐 matchRemoteFailureHint()，取输出最后 400 行，按顺序返回第一条命中的下标 ----------
  function matchHintIndex(hints, stepCode, output) {
    if (!hints || !hints.length || !String(output || "").trim()) return -1;
    const tail = String(output).split(NL).slice(-400).join(NL).toLowerCase();
    for (let i = 0; i < hints.length; i++) {
      const h = hints[i];
      const steps = h.steps || [];
      const stepOk = !steps.length || steps.some((s) => String(s).toLowerCase() === String(stepCode || "").toLowerCase());
      const textOk = (h.containsAny || []).some((n) => String(n).trim() && tail.includes(String(n).toLowerCase()));
      if (stepOk && textOk && String(h.title || "").trim() && String(h.advice || "").trim()) return i;
    }
    return -1;
  }

  // ---------- 发布前校验：err 会被客户端丢弃或无法工作，warn 只是提醒 ----------
  function validateAnn(ann, now) {
    const out = [];
    if (!String(ann.id || "").trim()) out.push(["err", "缺少 ID"]);
    if (!String(ann.title || "").trim()) out.push(["err", "标题为空，客户端不会展示"]);
    if (!String(ann.body || "").trim()) out.push(["err", "正文为空，客户端不会展示"]);
    if (String(ann.actionTarget || "").trim() && !parseAction(ann.actionTarget)) out.push(["err", "按钮动作无效（只支持应用内入口或 https 链接）"]);
    if (ann.expiresAt != null && now >= ann.expiresAt) out.push(["warn", "已过期，不会再展示"]);
    const f = ann.filter || {};
    if (f.minAppVersion && !parseVersion(f.minAppVersion)) out.push(["warn", "最低版本号格式不对，客户端会忽略这条限制"]);
    if (f.maxAppVersion && !parseVersion(f.maxAppVersion)) out.push(["warn", "最高版本号格式不对，客户端会忽略这条限制"]);
    const a = parseVersion(f.minAppVersion), b = parseVersion(f.maxAppVersion);
    if (a && b && cmpVersion(a, b) > 0) out.push(["err", "最低版本高于最高版本，任何设备都看不到"]);
    if (ann.level === "blocker" && ann.dismissible === false) out.push(["warn", "不可关闭的强提醒：用户每次打开 App 都会弹出"]);
    return out;
  }
  function validateHint(h) {
    const out = [];
    if (!String(h.id || "").trim()) out.push(["err", "缺少 ID"]);
    if (!String(h.title || "").trim()) out.push(["err", "原因标题为空，不会生效"]);
    if (!String(h.advice || "").trim()) out.push(["err", "解决建议为空，不会生效"]);
    if (!(h.containsAny || []).some((n) => String(n).trim())) out.push(["err", "没有匹配关键字，永远不会命中"]);
    if (String(h.action || "").trim() && !parseAction(h.action)) out.push(["err", "按钮动作无效（只支持应用内入口或 https 链接）"]);
    return out;
  }

  // ---------- 渲染：手机外壳 + 各种呈现位置 ----------
  function phone(inner, clock) {
    return '<div class="pv-phone"><div class="pv-status"><span>' + esc(clock || "11:42") + '</span><span class="pv-sig">5G ▮▮▮ 87%</span></div><div class="pv-screen">' + inner + "</div></div>";
  }
  function mockRows(n) {
    let s = "";
    for (let i = 0; i < n; i++) s += '<div class="pv-row"><i></i><span style="width:' + (46 + ((i * 23) % 34)) + '%"></span></div>';
    return s;
  }
  function bannerCard(ann) {
    const act = actionText(ann.actionLabel, ann.actionTarget);
    const dot = ann.level === "warning" ? "#E8A064" : "#389BFF";
    return '<div class="pv-banner"><div class="pv-bh"><i style="background:' + dot + '"></i><b>' + esc(ann.title || "（标题）") + "</b>" +
      (ann.dismissible !== false ? '<span class="pv-x">✕</span>' : "") + "</div>" +
      '<div class="pv-body">' + esc(ann.body || "（正文）") + "</div>" +
      (act ? '<span class="pv-chip">' + esc(act) + "</span>" : "") + "</div>";
  }
  function renderBanner(ann) {
    return phone('<div class="pv-title">设置</div>' + bannerCard(ann) + mockRows(6));
  }
  function renderModal(ann) {
    const act = actionText(ann.actionLabel, ann.actionTarget);
    const btns = act
      ? '<span class="pv-btn sec">知道了</span><span class="pv-btn">' + esc(act) + "</span>"
      : '<span class="pv-btn">知道了</span>';
    return phone('<div class="pv-title">麦麦</div>' + mockRows(7) +
      '<div class="pv-dim"><div class="pv-dialog"><b>' + esc(ann.title || "（标题）") + '</b><div class="pv-body">' + esc(ann.body || "（正文）") + '</div><div class="pv-btns">' + btns + "</div></div></div>");
  }
  function renderNotify(ann) {
    return phone('<div class="pv-shade"><div class="pv-clock">11:42</div><div class="pv-date">11月20日 星期五</div>' +
      '<div class="pv-noti"><div class="pv-nh"><span class="pv-ico">M</span>MaiDroid · 远程公告 · 现在</div><b>' + esc(ann.title || "（标题）") + '</b><div class="pv-body">' + esc(ann.body || "（正文）") + "</div></div>" +
      '<div class="pv-noti ghost"></div></div>');
  }
  function renderHint(h, stepLabel) {
    const p = parseAction(h.action);
    const code = (h.steps && h.steps[0]) || "QQ-INSTALL";
    const btns = (p ? '<span class="pv-nb hi">' + esc(DEFAULT_LABEL[p.kind]) + "</span>" : "") +
      '<span class="pv-nb hi">生成求助码</span><span class="pv-nb">反馈问题</span><span class="pv-nb">QQ 群反馈</span>';
    return phone('<div class="pv-title">首次引导</div><div class="pv-fail"><div class="pv-fh">✕ ' + esc(stepLabel || "某个步骤") + "失败</div>" +
      '<div class="pv-code">错误码 ' + esc(code) + "</div>" +
      '<div class="pv-cause">可能的原因：' + esc(h.title || "（原因标题）") + "</div>" +
      '<div class="pv-body">' + esc(h.advice || "（解决建议）") + '</div><div class="pv-nbs">' + btns + "</div></div>" + mockRows(3));
  }

  C.mfp = { parseAction, actionText, parseVersion, cmpVersion, annMissReason, matchHintIndex, validateAnn, validateHint, renderBanner, renderModal, renderNotify, renderHint, DEFAULT_LABEL };
})();
`;
