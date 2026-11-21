// 控制台“05 成员”“06 审计”两个分页（仅所有者可见，服务端同样按所有者校验）。
// 触发：所有者点开对应分页时才拉数据；控制台下发时由服务端把这里的标记拼进 <!--TEAM--> 占位、脚本接在主脚本后面。
// 成员：新建账号 -> 生成只显示一次的初始口令 -> 私聊转交 -> 对方首次登录强制改口令；可改角色、停用、重置口令、删除。
// 审计：最近 200 条操作记录，可按类别与操作人筛选。
// 注意：这里是 TS 模板字符串，脚本里不写反斜杠和美元加花括号。

export const ADMIN_TEAM_MARKUP = `
  <section class="view" id="v-team">
    <div class="row">
      <div class="panel f5">
        <div class="ph"><h2>新建成员</h2><span class="sp mono soft">初始口令只显示一次</span></div>
        <label class="fld"><span>账号</span><input id="tmUser" placeholder="小写字母 / 数字 / _ . -，3~32 位" autocomplete="off" spellcheck="false" /></label>
        <label class="fld"><span>显示名</span><input id="tmName" placeholder="控制台右上角与审计里展示" autocomplete="off" /></label>
        <label class="fld"><span>角色</span><select id="tmRole"></select></label>
        <div class="toolbar" style="margin-top:6px"><span class="sp"><button class="btn pri" data-act="tCreate">创建并生成初始口令</button></span></div>
        <div id="tmResult"></div>
      </div>
      <div class="panel f7">
        <div class="ph"><h2>角色权限</h2><span class="sp mono soft">逐级包含</span></div>
        <div class="roles">
          <div class="rc" style="border-top-color:var(--mute)"><b>观察者</b><span>只看总览大屏</span></div>
          <div class="rc" style="border-top-color:var(--teal)"><b>客服</b><span>+ 查求助码、看报错详情、复制回复话术</span></div>
          <div class="rc" style="border-top-color:var(--mustard)"><b>维护者</b><span>+ 改报错状态、编辑并签名发布公告 / 热规则</span></div>
          <div class="rc" style="border-top-color:var(--orange)"><b>所有者</b><span>+ 成员管理、清空 debug、查看审计</span></div>
        </div>
        <div class="soft" style="font-size:12.5px;margin-top:14px">所有者账号只能由超级管理员（ADMIN_TOKEN）授予和管理；成员被停用、改角色或重置口令后立即强制下线。</div>
      </div>
    </div>
    <div class="panel" data-busy="team">
      <div class="ph"><h2>成员列表</h2><span class="sp toolbar"><button class="btn sm" data-act="tReload">刷新</button></span></div>
      <div id="tmList"></div>
    </div>
  </section>

  <section class="view" id="v-audit">
    <div class="panel" data-busy="audit">
      <div class="ph"><h2>审计日志</h2>
        <div class="seg" id="auSeg">
          <button class="on" data-act="tAudit" data-v="all">全部</button><button data-act="tAudit" data-v="login">登录</button><button data-act="tAudit" data-v="diag">求助码</button><button data-act="tAudit" data-v="error">报错</button><button data-act="tAudit" data-v="manifest">清单</button><button data-act="tAudit" data-v="team">成员</button>
        </div>
        <input id="auActor" placeholder="按操作人筛选" style="width:160px" autocomplete="off" />
        <span class="sp mono soft">最近 200 条 · 保留 90 天</span>
      </div>
      <div id="auList"></div>
    </div>
  </section>`;

export const ADMIN_TEAM_SCRIPT = `
(function () {
  "use strict";
  const C = window.MDC;
  const MD = window.MD;
  const $ = (id) => document.getElementById(id);
  // 非所有者拿不到这两个分页的标记，脚本直接退出
  if (!$("v-team")) return;
  const esc = C.esc;
  const ts = { users: [], audit: [], group: "all", loadedTeam: false };

  const ACTION = {
    login: ["登录", "login"], login_fail: ["登录失败", "login"], logout: ["退出", "login"], password_change: ["修改口令", "login"],
    diag_lookup: ["查询求助码", "diag"], error_status: ["修改报错状态", "error"], debug_clear: ["清空 debug", "error"],
    manifest_publish: ["发布清单", "manifest"], user_create: ["新建成员", "team"], user_update: ["修改成员", "team"], user_delete: ["删除成员", "team"],
  };
  const DETAIL_KEY = { role: "角色", disabled: "停用", displayName: "显示名", resetPassword: "重置口令", status: "状态", found: "查到", announcements: "公告", failureHints: "热规则", issues: "问题", samples: "样本" };

  // 所有者只能授予观察者 / 客服 / 维护者；超级管理员才能授予所有者
  function roleOptions(selected) {
    return ["viewer", "support", "maintainer", "owner"]
      .filter((r) => r !== "owner" || MD.me.root || selected === "owner")
      .map((r) => '<option value="' + r + '"' + (r === selected ? " selected" : "") + ">" + C.ROLE_NAME[r] + "</option>").join("");
  }
  function initial(name) { return esc(String(name || "?").slice(0, 1).toUpperCase()); }

  // ---------- 成员 ----------
  async function loadTeam() {
    ts.loadedTeam = true;
    $("tmRole").innerHTML = roleOptions("support");
    C.busy("team", true);
    try {
      const d = await C.api("/admin/api/users");
      ts.users = d.users || [];
      renderTeam();
    } catch (e) { if (e.message !== "unauthorized") C.toast("成员列表加载失败：" + e.message, true); }
    finally { C.busy("team", false); }
  }

  function renderTeam() {
    const rootRow = '<div class="trow"><span class="av" style="background:var(--orange)">#</span><div><div class="t">超级管理员</div><div class="m">root · ADMIN_TOKEN · 不可修改</div></div>' +
      '<span class="tag role-owner">所有者</span><span class="m">应急入口</span><span></span></div>';
    const rows = ts.users.map((u) => {
      const self = u.id === MD.me.userId;
      const manageable = !self && (u.role !== "owner" || MD.me.root);
      const state = u.disabled ? '<span class="tag">已停用</span>' : u.mustChange ? '<span class="tag warn">待改口令</span>' : '<span class="tag ok">正常</span>';
      const role = manageable
        ? '<select data-act="tRole" data-id="' + u.id + '">' + roleOptions(u.role) + "</select>"
        : '<span class="tag role-' + esc(u.role) + '">' + esc(C.ROLE_NAME[u.role] || u.role) + "</span>";
      const ops = manageable
        ? '<button class="btn sm" data-act="tReset" data-id="' + u.id + '">重置口令</button>' +
          '<button class="btn sm" data-act="tToggle" data-id="' + u.id + '">' + (u.disabled ? "启用" : "停用") + "</button>" +
          '<button class="btn sm danger" data-act="tDelete" data-id="' + u.id + '">删除</button>'
        : '<span class="m">' + (self ? "这是你" : "需超级管理员") + "</span>";
      return '<div class="trow' + (u.disabled ? " off" : "") + '"><span class="av">' + initial(u.displayName) + "</span>" +
        '<div style="min-width:0"><div class="t">' + esc(u.displayName) + " " + state + '</div><div class="m">' + esc(u.username) + " · 最近登录 " + C.ago(u.lastLoginAt) + "</div></div>" +
        role + '<span class="m">创建于 ' + C.fmtTime(u.createdAt).slice(0, 10) + '</span><span class="toolbar">' + ops + "</span></div>";
    }).join("");
    $("tmList").innerHTML = rootRow + (rows || '<div class="empty">// 还没有成员，从左上角新建一个</div>');
  }

  function showSecret(title, username, pw) {
    $("tmResult").innerHTML =
      '<div class="secret"><div class="soft">' + esc(title) + " · <b>" + esc(username) + "</b></div>" +
      '<div class="codebar"><code id="tmPw">' + esc(pw) + '</code><button class="btn" data-act="tCopy">复制</button></div>' +
      '<div class="fmsg">只显示这一次。请私聊转交，对方首次登录后必须改成自己的口令。</div></div>';
    ts.lastSecret = "账号：" + username + "  初始口令：" + pw + "  登录：" + location.origin + "/admin";
  }

  async function createMember() {
    const username = $("tmUser").value.trim().toLowerCase();
    if (!username) { C.toast("先填账号", true); return; }
    try {
      const d = await C.api("/admin/api/users", { method: "POST", body: { username, displayName: $("tmName").value.trim(), role: $("tmRole").value } });
      showSecret("已创建成员", d.user.username, d.initialPassword);
      $("tmUser").value = ""; $("tmName").value = "";
      C.toast("成员 " + d.user.username + " 已创建");
      loadTeam();
    } catch (e) { if (e.message !== "unauthorized") C.toast("创建失败：" + e.message, true); }
  }

  async function patchMember(id, body, okMsg) {
    try {
      const d = await C.api("/admin/api/users/" + id, { method: "PATCH", body });
      if (d.initialPassword) showSecret("已重置口令", d.user.username, d.initialPassword);
      C.toast(okMsg);
      loadTeam();
    } catch (e) { if (e.message !== "unauthorized") C.toast("操作失败：" + e.message, true); loadTeam(); }
  }

  function userById(id) { return ts.users.find((u) => String(u.id) === String(id)); }

  // ---------- 审计 ----------
  async function loadAudit() {
    C.busy("audit", true);
    try {
      const d = await C.api("/admin/api/audit");
      ts.audit = d.entries || [];
      renderAudit();
    } catch (e) { if (e.message !== "unauthorized") C.toast("审计日志加载失败：" + e.message, true); }
    finally { C.busy("audit", false); }
  }

  function detailText(raw) {
    if (!raw) return "";
    try {
      const o = JSON.parse(raw);
      return Object.keys(o).map((k) => (DETAIL_KEY[k] || k) + " " + (o[k] === true ? "是" : o[k] === false ? "否" : (C.ROLE_NAME[o[k]] || o[k]))).join(" · ");
    } catch (e) { return raw; }
  }

  function renderAudit() {
    const actor = $("auActor").value.trim().toLowerCase();
    const list = ts.audit.filter((e) => {
      const a = ACTION[e.action];
      if (ts.group !== "all" && (!a || a[1] !== ts.group)) return false;
      return !actor || String(e.actor).toLowerCase().includes(actor);
    });
    if (!list.length) { $("auList").innerHTML = '<div class="empty">// 没有匹配的审计记录</div>'; return; }
    $("auList").innerHTML = list.map((e) => {
      const a = ACTION[e.action] || [e.action, ""];
      const bad = e.action === "login_fail";
      return '<div class="arow' + (bad ? " bad" : "") + '"><span class="m">' + C.fmtTime(e.created_at) + "</span>" +
        '<span><b>' + esc(e.actor) + "</b> " + (C.ROLE_NAME[e.role] ? '<span class="tag role-' + esc(e.role) + '">' + C.ROLE_NAME[e.role] + "</span>" : "") + "</span>" +
        '<span class="act">' + esc(a[0]) + "</span>" +
        '<span class="mono">' + esc(e.target || "") + "</span>" +
        '<span class="m">' + esc(detailText(e.detail)) + "</span></div>";
    }).join("");
  }

  C.views.team = () => { if (!ts.loadedTeam) loadTeam(); };
  C.views.audit = loadAudit;

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el) return;
    const a = el.dataset.act;
    if (a === "tCreate") createMember();
    else if (a === "tReload") loadTeam();
    else if (a === "tCopy") navigator.clipboard.writeText(ts.lastSecret || "").then(() => C.toast("账号与初始口令已复制"), () => C.toast("复制失败，请手动选择", true));
    else if (a === "tReset") {
      const u = userById(el.dataset.id);
      if (u && confirm("重置 " + u.username + " 的口令？对方会被立即下线，需用新的初始口令重新登录。")) patchMember(u.id, { resetPassword: true }, "口令已重置");
    } else if (a === "tToggle") {
      const u = userById(el.dataset.id);
      if (u && (u.disabled || confirm("停用 " + u.username + "？对方会被立即下线。"))) patchMember(u.id, { disabled: !u.disabled }, u.disabled ? "已启用" : "已停用");
    } else if (a === "tDelete") {
      const u = userById(el.dataset.id);
      if (u && confirm("永久删除成员 " + u.username + "？审计记录会保留。")) {
        C.api("/admin/api/users/" + u.id, { method: "DELETE" }).then(() => { C.toast("已删除 " + u.username); loadTeam(); }, (err) => C.toast("删除失败：" + err.message, true));
      }
    } else if (a === "tAudit") {
      ts.group = el.dataset.v;
      $("auSeg").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b.dataset.v === ts.group));
      renderAudit();
    }
  });
  // 角色下拉改动即提交，改完对方立即下线并以新角色重新登录
  document.addEventListener("change", (e) => {
    const el = e.target;
    if (el.dataset && el.dataset.act === "tRole") {
      const u = userById(el.dataset.id);
      if (u && confirm("把 " + u.username + " 改为「" + C.ROLE_NAME[el.value] + "」？对方会被立即下线。")) patchMember(u.id, { role: el.value }, "角色已更新");
      else renderTeam();
    }
  });
  $("auActor").addEventListener("input", renderAudit);
})();
`;
