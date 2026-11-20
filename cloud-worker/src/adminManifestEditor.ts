// 控制台“04 清单”可视化编辑器：左侧条目列表、中间表单、右侧手机实时预览与模拟匹配，可随时切到 JSON 源码模式。
// 触发：维护者及以上打开“04 清单”时加载线上清单；所有改动先落在本地草稿，点“ECDSA 签名并发布”才推送到全部客户端。
// 顺序：拉取线上清单 -> 规整成草稿 -> 表单编辑（每次输入同步刷新列表、校验与预览）-> 发布前汇总新增 / 删除 / 修改并二次确认。
// 注意：这里是 TS 模板字符串，脚本里不写反斜杠和美元加花括号。

export const ADMIN_MANIFEST_MARKUP = `
  <section class="view" id="v-manifest">
    <div class="panel" data-busy="manifest">
      <div class="ph"><h2>远程公告与排障热规则</h2>
        <div class="seg" id="mfMode"><button class="on" data-act="mfMode" data-v="ui">可视化</button><button data-act="mfMode" data-v="json">JSON</button></div>
        <span class="sp mono soft" id="manifestStatus">未加载</span></div>
      <div class="chips" id="manChips"></div>
      <div class="toolbar" style="margin-top:20px">
        <button class="btn sm" data-act="mfAddAnn">+ 新公告</button>
        <button class="btn sm" data-act="mfAddHint">+ 新热规则</button>
        <button class="btn sm" data-act="mfRevert">放弃未发布改动</button>
        <button class="btn sm" data-act="mfDownload">下载已签名清单</button>
        <span class="sp"><button class="btn pri" data-act="mfPublish">ECDSA 签名并发布</button></span>
      </div>
    </div>
    <div class="mf-ui" id="mfUi">
      <div class="panel mf-list" id="mfList"></div>
      <div class="panel mf-form" id="mfForm"></div>
      <div class="panel mf-prev"><div class="ph"><h2>实时预览</h2><div class="sp seg" id="pvSeg"></div></div><div id="pvBox"></div><div id="pvSim"></div></div>
    </div>
    <div class="panel" id="mfJson" hidden>
      <div class="ph"><h2>JSON 源码</h2><span class="sp mono soft" id="mfJsonState"></span></div>
      <textarea class="term" id="manifestEditor" spellcheck="false"></textarea>
    </div>
  </section>`;

export const ADMIN_MANIFEST_EDITOR_SCRIPT = `
(function () {
  "use strict";
  const C = window.MDC;
  const P = C.mfp;
  const $ = (id) => document.getElementById(id);
  if (!$("v-manifest")) return;
  const esc = C.esc;
  const NL = String.fromCharCode(10);

  // 失败步骤码（与 App 端 FailureStep 一致），热规则按这些步骤限定生效范围
  const STEP_GROUPS = [
    ["容器", [["CT-UNPACK", "解包容器资源"], ["CT-PROOT", "启动 proot"], ["CT-STORAGE", "配置共享存储入口"], ["CT-DNS", "配置容器 DNS"], ["APT-MIRROR", "配置 Debian 软件源"], ["APT-INSTALL", "安装 Git、curl 和证书"], ["CT-CHECK", "核对容器文件"], ["MD-OOBE", "首次引导意外出错"]]],
    ["MaiBot", [["MB-CLONE", "下载 MaiBot 源码"], ["UV-INSTALL", "安装 uv"], ["PY-INSTALL", "准备 Python 解释器"], ["UV-SYNC", "安装 Python 依赖包"], ["UV-VENV", "核对 Python 依赖"], ["MB-BLOCKED", "MaiBot 启动前检查"], ["MB-CRASH", "MaiBot 启动后反复退出"], ["MB-NOSTART", "MaiBot 进程没有启动"], ["MB-TIMEOUT", "等待 MaiBot WebUI 超时"], ["UP-PRECHECK", "在线更新前检查"], ["UP-FETCH", "获取上游 MaiBot 版本"], ["UP-RESET", "切换 MaiBot 源码"]]],
    ["NapCat", [["QQ-PRECHECK", "安装 NapCat 前检查"], ["QQ-INSTALL", "安装 NapCat"], ["QQ-ADAPTER", "安装 QQ 适配器"], ["QQ-CONFIG", "配置 QQ 适配器"], ["QQ-FILES", "核对 NapCat 文件"]]],
    ["SnowLuma", [["SL-PRECHECK", "安装 SnowLuma 前检查"], ["SL-INSTALL", "安装 SnowLuma 和 QQ"], ["SL-ADAPTER", "安装 QQ 适配器（SnowLuma）"], ["SL-CONFIG", "配置 QQ 适配器（SnowLuma）"], ["SL-FILES", "核对 SnowLuma 文件"]]],
    ["官方机器人", [["QQO-PRECHECK", "安装 QQ 官方机器人适配器前检查"], ["QQO-INSTALL", "安装 QQ 官方机器人适配器"], ["QQO-CONFIG", "写入 QQ 官方机器人配置"], ["QQO-CONNECT", "连接 QQ 开放平台"]]],
    ["SSH / 应用", [["SSH-CONFIG", "容器 SSH 配置"], ["SSH-SETUP", "准备容器 SSH"], ["SSH-START", "启动容器 sshd"], ["APP-CHECK", "检查 MaiDroid 新版本"], ["APP-DOWNLOAD", "下载 MaiDroid 安装包"], ["APP-VERIFY", "校验 MaiDroid 安装包"], ["APP-INSTALL", "打开系统安装器"], ["MD-CLEANUP", "清理空间"], ["MD-CRASH", "MaiDroid 闪退"]]],
  ];
  const STEP_LABEL = {};
  STEP_GROUPS.forEach((g) => g[1].forEach((s) => { STEP_LABEL[s[0]] = s[1]; }));
  const ACTIONS = [["", "不放按钮"], ["open:app_update", "检查应用更新"], ["open:maibot_update", "打开 MaiBot 更新"], ["open:cleanup", "清理空间"], ["open:qq_group", "QQ 群反馈"], ["open:permissions", "应用权限设置"], ["url", "打开网页链接（https）"]];
  const BRANDS = ["HONOR", "HUAWEI", "Xiaomi", "OPPO", "vivo", "OnePlus", "realme", "samsung", "meizu", "nubia"];
  const SDKS = [[26, "8.0"], [27, "8.1"], [28, "9"], [29, "10"], [30, "11"], [31, "12"], [32, "12L"], [33, "13"], [34, "14"], [35, "15"], [36, "16"]];
  const LEVELS = [["info", "普通横幅", "#389BFF"], ["warning", "警示横幅", "#E8A064"], ["blocker", "强提醒弹窗", "#E97F0F"]];

  const ms = {
    loaded: false, version: null, server: null, draft: { announcements: [], failureHints: [] },
    sel: null, mode: "ui", pv: "banner",
    sim: { appVersion: "0.2.5", manufacturer: "HONOR", sdkInt: 34, activeQqClient: "snowluma", setupComplete: true },
    test: { step: "", log: "" },
  };
  const nowSec = () => Math.floor(Date.now() / 1000);

  // ---------- 草稿规整：补齐表单要用的字段，保留未知字段原样发布 ----------
  function normAnn(a) {
    const f = Object.assign({ minAppVersion: null, maxAppVersion: null, manufacturers: [], minSdkInt: null, activeQqClients: [], onlyDuringOobe: false }, a.filter || {});
    return Object.assign({ id: "", level: "info", title: "", body: "", actionLabel: null, actionTarget: null, notifySystemBar: false, dismissible: true, expiresAt: null }, a, { filter: f });
  }
  function normHint(h) {
    return Object.assign({ id: "", steps: [], containsAny: [], title: "", advice: "", action: null }, h);
  }
  function normPayload(p) {
    p = p || {};
    return {
      announcements: (Array.isArray(p.announcements) ? p.announcements : []).map(normAnn),
      failureHints: (Array.isArray(p.failureHints) ? p.failureHints : []).map(normHint),
    };
  }
  function rid(prefix) {
    const d = new Date();
    const ymd = d.getFullYear() + String(d.getMonth() + 1).padStart(2, "0") + String(d.getDate()).padStart(2, "0");
    return prefix + "-" + ymd + "-" + Math.random().toString(36).slice(2, 6);
  }
  function cur() {
    if (!ms.sel) return null;
    const list = ms.sel.kind === "ann" ? ms.draft.announcements : ms.draft.failureHints;
    return list[ms.sel.i] || null;
  }
  function isDirty() { return JSON.stringify(ms.draft) !== JSON.stringify(ms.server); }

  // ---------- 加载 ----------
  async function load(force) {
    if (ms.loaded && !force) return;
    C.busy("manifest", true);
    try {
      const d = await C.api("/admin/api/manifest");
      ms.version = d.version;
      ms.server = normPayload(d.payload);
      ms.draft = JSON.parse(JSON.stringify(ms.server));
      ms.loaded = true;
      ms.sel = ms.draft.announcements.length ? { kind: "ann", i: 0 } : ms.draft.failureHints.length ? { kind: "hint", i: 0 } : null;
      $("manifestStatus").textContent = "线上 v" + d.version + " · 发布即刻生效（边缘缓存自动失效）";
      renderAll();
    } catch (e) { if (e.message !== "unauthorized") C.toast("清单加载失败：" + e.message, true); }
    finally { C.busy("manifest", false); }
  }

  // ---------- 顶部统计 ----------
  function errorCount() {
    let n = 0;
    ms.draft.announcements.forEach((a) => { n += P.validateAnn(a, nowSec()).filter((x) => x[0] === "err").length; });
    ms.draft.failureHints.forEach((h) => { n += P.validateHint(h).filter((x) => x[0] === "err").length; });
    return n;
  }
  function renderChips() {
    const errs = errorCount();
    const dirty = isDirty();
    const live = ms.draft.announcements.filter((a) => !P.validateAnn(a, nowSec()).some((x) => x[0] === "err") && !(a.expiresAt != null && nowSec() >= a.expiresAt)).length;
    const chips = [
      ["线上版本", ms.version == null ? "-" : "v" + ms.version, "var(--orange)"],
      ["公告（生效）", ms.draft.announcements.length + "（" + live + "）", "var(--teal)"],
      ["排障热规则", ms.draft.failureHints.length, "var(--mustard)"],
      ["校验", errs ? errs + " 处错误" : "OK", errs ? "var(--red)" : "var(--olive)"],
      ["草稿", dirty ? "未发布" : "已同步", dirty ? "var(--plum)" : "var(--mute)"],
    ];
    $("manChips").innerHTML = chips.map((c) => '<div class="chip" style="border-top-color:' + c[2] + '"><div class="l">' + c[0] + '</div><div class="v">' + esc(c[1]) + "</div></div>").join("");
  }

  // ---------- 左侧列表 ----------
  function annStatus(a) {
    const v = P.validateAnn(a, nowSec());
    if (v.some((x) => x[0] === "err")) return ["有错误", "var(--red)"];
    if (a.expiresAt != null && nowSec() >= a.expiresAt) return ["已过期", "var(--mute)"];
    const f = a.filter;
    const targeted = f.minAppVersion || f.maxAppVersion || f.manufacturers.length || f.minSdkInt != null || f.activeQqClients.length || f.onlyDuringOobe;
    return [targeted ? "定向投放" : "全员可见", "var(--olive)"];
  }
  function renderList() {
    const item = (kind, i, dot, title, sub, stColor) =>
      '<div class="mf-item' + (ms.sel && ms.sel.kind === kind && ms.sel.i === i ? " on" : "") + '" data-act="mfSel" data-k="' + kind + '" data-i="' + i + '">' +
      '<i style="background:' + dot + '"></i><div style="min-width:0"><div class="t">' + esc(title || "（未命名）") + '</div><div class="m" style="color:' + stColor + '">' + esc(sub) + "</div></div></div>";
    const anns = ms.draft.announcements.map((a, i) => {
      const lv = LEVELS.find((l) => l[0] === a.level) || LEVELS[0];
      const s = annStatus(a);
      return item("ann", i, lv[2], a.title, lv[1] + " · " + s[0], s[1]);
    }).join("") || '<div class="empty">还没有公告</div>';
    const hints = ms.draft.failureHints.map((h, i) => {
      const bad = P.validateHint(h).some((x) => x[0] === "err");
      return item("hint", i, "var(--mustard)", h.title, (h.steps.length ? h.steps.join(" / ") : "所有步骤") + (bad ? " · 有错误" : ""), bad ? "var(--red)" : "var(--soft)");
    }).join("") || '<div class="empty">还没有热规则</div>';
    $("mfList").innerHTML = '<div class="mf-sec">公告 <span>' + ms.draft.announcements.length + "</span></div>" + anns +
      '<div class="mf-sec">排障热规则 <span>' + ms.draft.failureHints.length + "</span></div>" + hints +
      '<div class="mf-tip">强提醒弹窗一次只弹第一条命中的；热规则按顺序取第一条命中的，可在表单右上角调整顺序。</div>';
  }

  // ---------- 中间表单 ----------
  // 用 div 而不是 label 包裹：一个字段里常有多个按钮 / 输入框，label 会把点击转给第一个控件
  function fld(label, inner, hint) {
    return '<div class="fld"><span>' + label + "</span><div>" + inner + (hint ? '<div class="fh">' + hint + "</div>" : "") + "</div></div>";
  }
  function actionFields(raw) {
    const p = P.parseAction(raw);
    const kind = !String(raw || "").trim() ? "" : p && p.kind === "url" ? "url" : p ? String(raw).trim().toLowerCase() : "url";
    const url = p && p.kind === "url" ? p.url : kind === "url" ? String(raw || "").replace(/^url:/i, "") : "";
    return '<select data-f="actionKind">' + ACTIONS.map((a) => '<option value="' + a[0] + '"' + (a[0] === kind ? " selected" : "") + ">" + a[1] + "</option>").join("") + "</select>" +
      '<input data-f="actionUrl" placeholder="https://..." value="' + esc(url) + '" style="margin-top:8px"' + (kind === "url" ? "" : " hidden") + " />";
  }
  function toLocalInput(sec) {
    if (sec == null) return "";
    const d = new Date(sec * 1000);
    const p = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + "T" + p(d.getHours()) + ":" + p(d.getMinutes());
  }
  function formHead(kind, title) {
    return '<div class="ph"><h2>' + title + '</h2><span class="sp toolbar">' +
      '<button class="btn sm" data-act="mfMove" data-d="-1" title="上移">↑</button><button class="btn sm" data-act="mfMove" data-d="1" title="下移">↓</button>' +
      '<button class="btn sm" data-act="mfDup">复制</button><button class="btn sm danger" data-act="mfDel">删除</button></span></div>';
  }
  function issuesBox(list) {
    return '<div class="mf-issues" id="mfIssues">' + list.map((x) => '<div class="' + x[0] + '">' + (x[0] === "err" ? "✕ " : "! ") + esc(x[1]) + "</div>").join("") + "</div>";
  }
  function renderForm() {
    const it = cur();
    if (!it) { $("mfForm").innerHTML = '<div class="empty">// 从左侧选一条，或点上方“+ 新公告 / + 新热规则”</div>'; return; }
    if (ms.sel.kind === "ann") {
      const f = it.filter;
      $("mfForm").innerHTML = formHead("ann", "编辑公告") + issuesBox(P.validateAnn(it, nowSec())) +
        fld("标题", '<input data-f="title" maxlength="60" value="' + esc(it.title) + '" placeholder="一句话说清楚发生了什么" />') +
        fld("正文", '<textarea data-f="body" rows="4" placeholder="面向用户的说明，支持换行">' + esc(it.body) + "</textarea>") +
        fld("呈现方式", '<div class="seg">' + LEVELS.map((l) => '<button data-act="mfLevel" data-v="' + l[0] + '" class="' + (it.level === l[0] ? "on" : "") + '">' + l[1] + "</button>").join("") + "</div>",
          "横幅出现在设置页和首次引导页顶部；强提醒弹窗在打开 App 时直接弹出") +
        fld("选项", '<label class="chk"><input type="checkbox" data-f="dismissible"' + (it.dismissible !== false ? " checked" : "") + " /> 允许用户关闭</label>" +
          '<label class="chk" style="margin-left:18px"><input type="checkbox" data-f="notifySystemBar"' + (it.notifySystemBar ? " checked" : "") + " /> 同时推送系统通知（每台设备只推一次）</label>") +
        fld("按钮动作", actionFields(it.actionTarget)) +
        fld("按钮文字", '<input data-f="actionLabel" value="' + esc(it.actionLabel || "") + '" placeholder="留空用默认文字" />') +
        fld("过期时间", '<input type="datetime-local" data-f="expiresAt" value="' + toLocalInput(it.expiresAt) + '" />' +
          '<div class="mf-quick">' + [[1, "1 天"], [3, "3 天"], [7, "7 天"], [30, "30 天"], [0, "永不过期"]].map((q) => '<button class="btn sm" data-act="mfExp" data-d="' + q[0] + '">' + q[1] + "</button>").join("") + "</div>") +
        '<div class="mf-sub">定向投放 <span>全部留空 = 所有设备</span></div>' +
        fld("App 版本", '<div class="mf-pair"><input data-f="minAppVersion" placeholder="最低，如 0.2.3" value="' + esc(f.minAppVersion || "") + '" /><span>~</span><input data-f="maxAppVersion" placeholder="最高，如 0.2.5" value="' + esc(f.maxAppVersion || "") + '" /></div>') +
        fld("手机品牌", '<input data-f="manufacturers" placeholder="逗号分隔，不区分大小写" value="' + esc(f.manufacturers.join(", ")) + '" />' +
          '<div class="mf-quick">' + BRANDS.map((b) => '<button class="btn sm' + (f.manufacturers.some((m) => m.toLowerCase() === b.toLowerCase()) ? " on" : "") + '" data-act="mfBrand" data-v="' + b + '">' + b + "</button>").join("") + "</div>") +
        fld("最低系统", '<select data-f="minSdkInt"><option value="">不限</option>' + SDKS.map((s) => '<option value="' + s[0] + '"' + (f.minSdkInt === s[0] ? " selected" : "") + ">Android " + s[1] + "（API " + s[0] + "）</option>").join("") + "</select>") +
        fld("QQ 协议端", ["napcat", "snowluma"].map((q) => '<label class="chk" style="margin-right:18px"><input type="checkbox" data-f="qq" data-v="' + q + '"' + (f.activeQqClients.includes(q) ? " checked" : "") + " /> " + (q === "napcat" ? "NapCat" : "SnowLuma") + "</label>").join(""), "都不勾 = 不限；勾了之后没装协议端的设备看不到") +
        fld("引导阶段", '<label class="chk"><input type="checkbox" data-f="onlyDuringOobe"' + (f.onlyDuringOobe ? " checked" : "") + " /> 只给还没完成首次引导的设备看</label>") +
        fld("公告 ID", '<input data-f="id" class="mono" value="' + esc(it.id) + '" />', "用户关闭过的公告按 ID 记住；改 ID 等于重新推给所有人");
    } else {
      $("mfForm").innerHTML = formHead("hint", "编辑排障热规则") + issuesBox(P.validateHint(it)) +
        fld("原因标题", '<input data-f="title" maxlength="60" value="' + esc(it.title) + '" placeholder="显示为“可能的原因：……”" />') +
        fld("解决建议", '<textarea data-f="advice" rows="4" placeholder="给用户看的分步解决办法">' + esc(it.advice) + "</textarea>") +
        fld("匹配关键字", '<textarea data-f="containsAny" rows="3" class="mono" placeholder="一行一个，命中任意一个即可">' + esc(it.containsAny.join(NL)) + "</textarea>",
          "在失败输出的最后 400 行里按字面查找，不区分大小写，不支持正则") +
        fld("限定步骤", '<div class="mf-steps">' + STEP_GROUPS.map((g) => '<div><em>' + g[0] + "</em>" + g[1].map((s) =>
          '<button class="btn sm' + (it.steps.includes(s[0]) ? " on" : "") + '" data-act="mfStep" data-v="' + s[0] + '" title="' + esc(s[1]) + '">' + s[0] + "</button>").join("") + "</div>").join("") + "</div>",
          it.steps.length ? "已限定 " + it.steps.length + " 个步骤" : "一个都不选 = 所有步骤都会尝试匹配") +
        fld("按钮动作", actionFields(it.action), "热规则的按钮固定显示默认文字") +
        fld("规则 ID", '<input data-f="id" class="mono" value="' + esc(it.id) + '" />');
    }
  }

  // ---------- 右侧预览与模拟 ----------
  function renderPreview() {
    const it = cur();
    if (!it) { $("pvSeg").innerHTML = ""; $("pvBox").innerHTML = '<div class="empty">// 暂无可预览内容</div>'; $("pvSim").innerHTML = ""; return; }
    if (ms.sel.kind === "ann") {
      const opts = [it.level === "blocker" ? ["modal", "弹窗"] : ["banner", "横幅"]];
      if (it.notifySystemBar) opts.push(["notify", "通知栏"]);
      if (!opts.some((o) => o[0] === ms.pv)) ms.pv = opts[0][0];
      $("pvSeg").innerHTML = opts.map((o) => '<button data-act="mfPv" data-v="' + o[0] + '" class="' + (ms.pv === o[0] ? "on" : "") + '">' + o[1] + "</button>").join("");
      $("pvBox").innerHTML = ms.pv === "modal" ? P.renderModal(it) : ms.pv === "notify" ? P.renderNotify(it) : P.renderBanner(it);
      renderSim();
    } else {
      $("pvSeg").innerHTML = "";
      $("pvBox").innerHTML = P.renderHint(it, STEP_LABEL[it.steps[0]] || (it.steps.length ? it.steps[0] : "安装 NapCat"));
      renderHintTest();
    }
  }
  function renderSim() {
    const s = ms.sim;
    if (!$("simVer")) {
      $("pvSim").innerHTML = '<div class="mf-sub">模拟设备 <span>看看这台设备能不能收到</span></div>' +
        '<div class="mf-sim"><input id="simVer" data-sim="appVersion" value="' + esc(s.appVersion) + '" placeholder="App 版本" />' +
        '<input id="simBrand" data-sim="manufacturer" value="' + esc(s.manufacturer) + '" placeholder="品牌" />' +
        '<input data-sim="sdkInt" type="number" value="' + s.sdkInt + '" placeholder="API" />' +
        '<select data-sim="activeQqClient"><option value="">未装协议端</option><option value="napcat"' + (s.activeQqClient === "napcat" ? " selected" : "") + '>NapCat</option><option value="snowluma"' + (s.activeQqClient === "snowluma" ? " selected" : "") + ">SnowLuma</option></select>" +
        '<label class="chk"><input type="checkbox" data-sim="setupComplete"' + (s.setupComplete ? " checked" : "") + " /> 已完成引导</label></div>" +
        '<div id="simOut" class="mf-out"></div>';
    }
    const miss = P.annMissReason(cur(), Object.assign({ now: nowSec() }, s));
    $("simOut").className = "mf-out " + (miss ? "no" : "yes");
    $("simOut").textContent = miss ? "✕ 收不到：" + miss : "✓ 这台设备会收到";
  }
  function renderHintTest() {
    const t = ms.test;
    if (!$("htLog")) {
      $("pvSim").innerHTML = '<div class="mf-sub">匹配测试 <span>粘贴一段失败输出试试</span></div>' +
        '<select id="htStep" data-ht="step"><option value="">选择失败步骤</option>' + STEP_GROUPS.map((g) => g[1].map((s) => '<option value="' + s[0] + '"' + (t.step === s[0] ? " selected" : "") + ">" + s[0] + " · " + esc(s[1]) + "</option>").join("")).join("") + "</select>" +
        '<textarea id="htLog" data-ht="log" rows="5" class="mono" placeholder="把用户截图里的报错文字贴进来">' + esc(t.log) + "</textarea>" +
        '<div id="htOut" class="mf-out"></div>';
    }
    const out = $("htOut");
    if (!t.log.trim() || !t.step) { out.className = "mf-out"; out.textContent = "选好步骤并粘贴输出后自动判断"; return; }
    const idx = P.matchHintIndex(ms.draft.failureHints, t.step, t.log);
    if (idx === ms.sel.i) { out.className = "mf-out yes"; out.textContent = "✓ 命中本条规则"; }
    else if (idx >= 0) { out.className = "mf-out warn"; out.textContent = "! 被排在前面的第 " + (idx + 1) + " 条抢先命中：" + (ms.draft.failureHints[idx].title || "（未命名）"); }
    else { out.className = "mf-out no"; out.textContent = "✕ 没有任何规则命中"; }
  }

  // 输入时只刷新列表、统计、校验和预览，不重绘表单，避免输入框丢焦点
  function refreshSide() {
    renderChips();
    renderList();
    const it = cur();
    if (it && $("mfIssues")) $("mfIssues").outerHTML = issuesBox(ms.sel.kind === "ann" ? P.validateAnn(it, nowSec()) : P.validateHint(it));
    renderPreview();
  }
  function renderAll() {
    renderChips();
    renderList();
    renderForm();
    $("pvSim").innerHTML = "";
    renderPreview();
  }

  // ---------- 表单字段写回草稿 ----------
  function setAction(it, key) {
    const kind = $("mfForm").querySelector('[data-f="actionKind"]').value;
    const urlEl = $("mfForm").querySelector('[data-f="actionUrl"]');
    urlEl.hidden = kind !== "url";
    const url = urlEl.value.trim();
    it[key] = kind === "url" ? (url ? "url:" + url : null) : kind || null;
  }
  function onField(e) {
    const el = e.target;
    const f = el.dataset && el.dataset.f;
    const it = cur();
    if (!f || !it) return;
    const isAnn = ms.sel.kind === "ann";
    const fl = isAnn ? it.filter : null;
    const v = el.value;
    if (f === "title" || f === "body" || f === "advice" || f === "id") it[f] = v;
    else if (f === "actionLabel") it.actionLabel = v.trim() ? v : null;
    else if (f === "dismissible" || f === "notifySystemBar") it[f] = el.checked;
    else if (f === "actionKind" || f === "actionUrl") setAction(it, isAnn ? "actionTarget" : "action");
    else if (f === "expiresAt") it.expiresAt = v ? Math.floor(new Date(v).getTime() / 1000) : null;
    else if (f === "minAppVersion" || f === "maxAppVersion") fl[f] = v.trim() || null;
    else if (f === "manufacturers") fl.manufacturers = v.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
    else if (f === "minSdkInt") fl.minSdkInt = v ? Number(v) : null;
    else if (f === "qq") fl.activeQqClients = Array.from($("mfForm").querySelectorAll('[data-f="qq"]:checked')).map((x) => x.dataset.v);
    else if (f === "onlyDuringOobe") fl.onlyDuringOobe = el.checked;
    else if (f === "containsAny") it.containsAny = v.split(NL).map((s) => s.trim()).filter(Boolean);
    refreshSide();
  }
  function onSim(e) {
    const el = e.target;
    if (el.dataset.sim) {
      const k = el.dataset.sim;
      ms.sim[k] = k === "setupComplete" ? el.checked : k === "sdkInt" ? Number(el.value) || 0 : el.value;
      renderSim();
    } else if (el.dataset.ht) {
      ms.test[el.dataset.ht] = el.value;
      renderHintTest();
    }
  }

  // ---------- 条目增删改序 ----------
  function select(kind, i) { ms.sel = { kind, i }; ms.pv = "banner"; renderAll(); }
  function addAnn() {
    ms.draft.announcements.unshift(normAnn({ id: rid("ann"), expiresAt: nowSec() + 7 * 86400 }));
    select("ann", 0);
  }
  function addHint(seed) {
    ms.draft.failureHints.unshift(normHint(Object.assign({ id: rid("hint") }, seed || {})));
    select("hint", 0);
  }
  function listOfSel() { return ms.sel.kind === "ann" ? ms.draft.announcements : ms.draft.failureHints; }
  function move(d) {
    const list = listOfSel();
    const j = ms.sel.i + d;
    if (j < 0 || j >= list.length) return;
    const t = list[ms.sel.i]; list[ms.sel.i] = list[j]; list[j] = t;
    ms.sel.i = j;
    renderAll();
  }
  function dup() {
    const list = listOfSel();
    const copy = JSON.parse(JSON.stringify(list[ms.sel.i]));
    copy.id = rid(ms.sel.kind === "ann" ? "ann" : "hint");
    list.splice(ms.sel.i + 1, 0, copy);
    select(ms.sel.kind, ms.sel.i + 1);
  }
  function del() {
    const list = listOfSel();
    const it = list[ms.sel.i];
    if (!confirm("删除「" + (it.title || it.id) + "」？发布前都可以“放弃未发布改动”找回。")) return;
    list.splice(ms.sel.i, 1);
    if (list.length) ms.sel.i = Math.min(ms.sel.i, list.length - 1);
    else ms.sel = ms.draft.announcements.length ? { kind: "ann", i: 0 } : ms.draft.failureHints.length ? { kind: "hint", i: 0 } : null;
    renderAll();
  }
  function toggleIn(arr, v) { const i = arr.indexOf(v); if (i >= 0) arr.splice(i, 1); else arr.push(v); }

  // ---------- JSON 模式 ----------
  function setMode(m) {
    if (m === ms.mode) return;
    if (m === "ui") {
      try { ms.draft = normPayload(JSON.parse($("manifestEditor").value)); }
      catch (e) { C.toast("JSON 语法错误，先修好再切回可视化：" + e.message, true); return; }
      if (ms.sel && !cur()) ms.sel = null;
    } else {
      $("manifestEditor").value = JSON.stringify(ms.draft, null, 2);
      $("mfJsonState").textContent = "JSON 语法 OK";
    }
    ms.mode = m;
    $("mfMode").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b.dataset.v === m));
    $("mfUi").hidden = m !== "ui";
    $("mfJson").hidden = m !== "json";
    if (m === "ui") renderAll();
  }
  // JSON 模式下保存/发布前先把源码解析回草稿
  function syncFromJson() {
    if (ms.mode !== "json") return true;
    try { ms.draft = normPayload(JSON.parse($("manifestEditor").value)); return true; }
    catch (e) { C.toast("JSON 语法错误：" + e.message, true); return false; }
  }

  // ---------- 发布 ----------
  function diffSummary() {
    const sum = (a, b) => {
      const am = new Map(a.map((x) => [x.id, JSON.stringify(x)]));
      const bm = new Map(b.map((x) => [x.id, JSON.stringify(x)]));
      let add = 0, rm = 0, mod = 0;
      bm.forEach((v, k) => { if (!am.has(k)) add++; else if (am.get(k) !== v) mod++; });
      am.forEach((v, k) => { if (!bm.has(k)) rm++; });
      return "新增 " + add + " · 删除 " + rm + " · 修改 " + mod;
    };
    return "公告：" + sum(ms.server.announcements, ms.draft.announcements) + NL + "热规则：" + sum(ms.server.failureHints, ms.draft.failureHints);
  }
  async function publish() {
    if (!ms.loaded || !syncFromJson()) return;
    if (!isDirty()) { C.toast("草稿和线上一致，无需发布"); return; }
    const errs = errorCount();
    const msg = "确认签名并发布到全部客户端？" + NL + NL + diffSummary() + (errs ? NL + NL + "⚠ 还有 " + errs + " 处错误，相关条目会被客户端丢弃或不生效。" : "");
    if (!confirm(msg)) return;
    C.busy("manifest", true);
    try {
      const d = await C.api("/admin/api/manifest", { method: "PUT", body: ms.draft });
      ms.version = d.version;
      ms.server = normPayload(d.payload);
      ms.draft = JSON.parse(JSON.stringify(ms.server));
      if (ms.mode === "json") $("manifestEditor").value = JSON.stringify(ms.draft, null, 2);
      $("manifestStatus").textContent = "已签名发布 v" + d.version;
      if (ms.sel && !cur()) ms.sel = null;
      renderAll();
      C.toast("清单 v" + d.version + " 已签名发布");
    } catch (e) { if (e.message !== "unauthorized") C.toast("发布失败：" + e.message, true); }
    finally { C.busy("manifest", false); }
  }

  // 报错详情里“生成热规则草稿”：带上步骤码和样本最后一行非空文本，直接打开表单待补充说明
  C.mfDraftHint = async (issue, samples) => {
    await load(false);
    if (!ms.loaded) return;
    if (ms.mode === "json") setMode("ui");
    const stepCode = String(issue.error_code || "").split("/")[0];
    const sample = (samples || [])[0];
    const last = sample && sample.context_text ? sample.context_text.split(NL).map((l) => l.trim()).filter(Boolean).slice(-1)[0] : "";
    addHint({ steps: STEP_LABEL[stepCode] ? [stepCode] : [], containsAny: [(last || issue.title || "").slice(0, 60)].filter(Boolean), title: "", advice: "" });
    if (STEP_LABEL[stepCode]) ms.test = { step: stepCode, log: sample && sample.context_text ? sample.context_text : "" };
    renderAll();
  };
  C.views.manifest = () => load(false);

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el || !$("v-manifest").contains(el)) return;
    const a = el.dataset.act;
    const it = cur();
    if (a === "mfMode") setMode(el.dataset.v);
    else if (a === "mfAddAnn") { if (syncFromJson()) { setMode("ui"); addAnn(); } }
    else if (a === "mfAddHint") { if (syncFromJson()) { setMode("ui"); addHint(); } }
    else if (a === "mfRevert") { if (ms.loaded && confirm("放弃所有未发布的改动，恢复成线上版本？")) { ms.draft = JSON.parse(JSON.stringify(ms.server)); if (ms.mode === "json") $("manifestEditor").value = JSON.stringify(ms.draft, null, 2); ms.sel = null; renderAll(); } }
    else if (a === "mfDownload") window.open("/v1/manifest.json", "_blank", "noopener");
    else if (a === "mfPublish") publish();
    else if (a === "mfSel") select(el.dataset.k, Number(el.dataset.i));
    else if (!it) return;
    else if (a === "mfLevel") { e.preventDefault(); it.level = el.dataset.v; renderForm(); refreshSide(); }
    else if (a === "mfExp") {
      e.preventDefault();
      const d = Number(el.dataset.d);
      it.expiresAt = d ? nowSec() + d * 86400 : null;
      $("mfForm").querySelector('[data-f="expiresAt"]').value = toLocalInput(it.expiresAt);
      refreshSide();
    } else if (a === "mfBrand") { e.preventDefault(); toggleIn(it.filter.manufacturers, el.dataset.v); renderForm(); refreshSide(); }
    else if (a === "mfStep") { e.preventDefault(); toggleIn(it.steps, el.dataset.v); renderForm(); refreshSide(); }
    else if (a === "mfMove") move(Number(el.dataset.d));
    else if (a === "mfDup") dup();
    else if (a === "mfDel") del();
    else if (a === "mfPv") { ms.pv = el.dataset.v; renderPreview(); }
  });
  $("mfForm").addEventListener("input", onField);
  $("mfForm").addEventListener("change", (e) => { if (e.target.type === "checkbox" || e.target.tagName === "SELECT") onField(e); });
  $("pvSim").addEventListener("input", onSim);
  $("pvSim").addEventListener("change", onSim);
  $("manifestEditor").addEventListener("input", () => {
    try { JSON.parse($("manifestEditor").value); $("mfJsonState").textContent = "JSON 语法 OK"; }
    catch (err) { $("mfJsonState").textContent = "JSON 语法错误：" + err.message; }
  });
  // 有未发布改动时离开页面先提醒
  window.addEventListener("beforeunload", (e) => { if (ms.loaded && (ms.mode === "json" || isDirty())) { syncFromJson(); if (isDirty()) { e.preventDefault(); e.returnValue = ""; } } });
})();
`;
