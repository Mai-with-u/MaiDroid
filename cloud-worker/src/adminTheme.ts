// 管理台视觉主题：复刻 status.maibot.top 的“复古工业遥测控制台”风格（暖色羊皮纸 / 浓缩咖啡双主题 + 深色仪表舱 KPI）。
// 只含样式、不含任何业务数据，登录闸门页与解锁后的控制台共用这一份；零外部字体与 CDN 依赖，缺字时回落到系统等宽 / 窄体字。
// 注意：这里是 TS 模板字符串，样式里不要出现反斜杠和美元加花括号。

export const ADMIN_CSS = `
.night { --bg:#17110E; --panel:#211915; --deck:#0D0908; --ink:#E6D7BD; --soft:#A59781; --line:rgba(230,215,189,.18); --track:rgba(230,215,189,.08); --orange:#EC6A32; --teal:#54A8AB; --mustard:#E2B03C; --mute:#6E6355; --brown:#B58A68; --olive:#A3B068; --plum:#C07CA8; --red:#F0563A; }
.day { --bg:#E6DCC6; --panel:#F1E9D8; --deck:#261E19; --ink:#231F1A; --soft:#6A6052; --line:rgba(35,31,26,.20); --track:rgba(35,31,26,.09); --orange:#D8521C; --teal:#1E5E62; --mustard:#D49B1A; --mute:#A99C86; --brown:#8A5A3C; --olive:#7D8B4A; --plum:#8E4F7A; --red:#C8361C; }
:root { --deck-line:rgba(243,217,164,.14); --nixie:#F3D9A4; --hot:#F0703A; --dlab:#B9A68A; --live:#58B868;
  --f-sans:'Noto Sans SC','PingFang SC','Microsoft YaHei UI','Microsoft YaHei',system-ui,sans-serif;
  --f-num:'Barlow Condensed','Bahnschrift Condensed','Bahnschrift','DIN Condensed','Agency FB','Arial Narrow',sans-serif;
  --f-mono:'IBM Plex Mono','Cascadia Mono','JetBrains Mono',Consolas,monospace; }
* { box-sizing:border-box; }
html, body { margin:0; }
body { background:var(--bg); color:var(--ink); font:14px/1.55 var(--f-sans); -webkit-font-smoothing:antialiased; transition:background .35s, color .35s; min-height:100vh; overflow-x:hidden; }
.bc { font-family:var(--f-num); font-stretch:condensed; font-variant-numeric:tabular-nums; }
.mono { font-family:var(--f-mono); }
.soft { color:var(--soft); }
::-webkit-scrollbar { width:10px; height:10px; }
::-webkit-scrollbar-track { background:var(--track); }
::-webkit-scrollbar-thumb { background:var(--mute); border:2px solid transparent; background-clip:padding-box; }
::-webkit-scrollbar-thumb:hover { background:var(--soft); background-clip:padding-box; }
::selection { background:var(--orange); color:#fff; }

/* 顶部三色信号条 */
.stripes { position:sticky; top:0; z-index:50; height:18px; overflow:hidden; background:linear-gradient(var(--orange) 0 6px, var(--mustard) 6px 12px, var(--teal) 12px 18px); }
.stripes::after { content:""; position:absolute; inset:0; background:linear-gradient(90deg, transparent, rgba(255,255,255,.35), transparent); width:30%; animation:sweep 6s linear infinite; mix-blend-mode:overlay; }
@keyframes sweep { from { transform:translateX(-100%); } to { transform:translateX(400%); } }

.wrap { max-width:1360px; margin:0 auto; padding:0 24px 48px; display:flex; flex-direction:column; gap:20px; }
.hd { display:flex; flex-wrap:wrap; align-items:center; gap:16px 40px; padding:24px 0 8px; }
.brand { display:flex; align-items:baseline; gap:14px; }
.brand b { font:700 44px/1 var(--f-num); font-stretch:condensed; letter-spacing:.02em; }
.brand b em { font-style:normal; color:var(--orange); }
.brand span { font-size:16px; color:var(--soft); letter-spacing:.12em; }
nav { display:flex; flex-wrap:wrap; gap:8px; }
nav a { display:flex; align-items:center; gap:10px; min-height:48px; padding:0 28px 0 16px; border-radius:4px; background:var(--panel); border-bottom:4px solid var(--line); color:var(--ink); text-decoration:none; font-weight:700; font-size:15px; cursor:pointer; user-select:none; transition:transform .12s, border-color .2s; }
nav a:hover { transform:translateY(-2px); border-bottom-color:var(--mustard); }
nav a i { font:400 12px var(--f-mono); font-style:normal; color:var(--soft); }
nav a.on { background:var(--ink); color:var(--bg); border-bottom-color:var(--orange); }
nav a .cnt { min-width:20px; height:20px; padding:0 6px; border-radius:10px; background:var(--orange); color:#fff; font:600 12px/20px var(--f-mono); text-align:center; }
.hd-r { margin-left:auto; display:flex; align-items:center; gap:16px; }
.upd { display:flex; align-items:center; gap:8px; font:13px var(--f-mono); color:var(--soft); }
.upd i { width:8px; height:8px; border-radius:50%; background:var(--live); box-shadow:0 0 8px rgba(88,184,104,.8); animation:pulse 2s ease-in-out infinite; }
@keyframes pulse { 50% { opacity:.35; } }
.btn { min-height:40px; padding:0 16px; border:1px solid var(--ink); border-radius:4px; background:transparent; color:var(--ink); font:700 13px var(--f-sans); cursor:pointer; transition:background .15s, color .15s, transform .1s; }
.btn:hover { background:var(--ink); color:var(--panel); }
.btn:active { transform:translateY(1px); }
.btn.pri { background:var(--orange); border-color:var(--orange); color:#fff; }
.btn.pri:hover { filter:brightness(1.1); }
.btn.danger { border-color:var(--red); color:var(--red); }
.btn.danger:hover { background:var(--red); color:#fff; }
.btn.sm { min-height:32px; padding:0 12px; font-size:12px; }
input, select, textarea { background:var(--bg); color:var(--ink); border:1px solid var(--line); border-radius:4px; padding:9px 12px; font:13px var(--f-sans); outline:none; transition:border-color .15s, box-shadow .15s; }
input:focus, select:focus, textarea:focus { border-color:var(--orange); box-shadow:0 0 0 3px color-mix(in srgb, var(--orange) 22%, transparent); }

/* 深色仪表舱 KPI：两种主题下都保持深色，数字带辉光管余辉 */
.deck { position:relative; overflow:hidden; background:var(--deck); border-radius:10px; padding:30px 12px; box-shadow:inset 0 3px 0 rgba(0,0,0,.45), inset 0 0 0 1px var(--deck-line); display:flex; flex-wrap:wrap; align-items:flex-end; gap:26px 0; }
.deck::before { content:""; position:absolute; inset:0; pointer-events:none; background:repeating-linear-gradient(0deg, rgba(255,255,255,.025) 0 1px, transparent 1px 3px); }
.deck::after { content:""; position:absolute; left:0; right:0; height:120px; top:-120px; pointer-events:none; background:linear-gradient(transparent, rgba(240,112,58,.06), transparent); animation:scan 7s linear infinite; }
@keyframes scan { to { top:110%; } }
.deck .k { flex:1 1 160px; padding:0 24px; border-left:1px solid var(--deck-line); position:relative; }
.deck .k:first-child { flex:1.35 1 220px; border-left:0; }
.deck .dl { display:flex; align-items:center; gap:8px; color:var(--dlab); font-size:13px; margin-bottom:6px; letter-spacing:.04em; }
.deck .dl i { width:8px; height:8px; border-radius:50%; background:var(--hot); box-shadow:0 0 10px rgba(236,106,50,.9); animation:pulse 1.6s ease-in-out infinite; }
.deck .dl i.live { background:var(--live); box-shadow:0 0 10px rgba(88,184,104,.9); }
.deck .dv { font:600 68px/1 var(--f-num); font-stretch:condensed; color:var(--nixie); text-shadow:0 0 22px rgba(240,170,70,.28); font-variant-numeric:tabular-nums; }
.deck .dv small { font-size:.5em; margin-left:4px; color:var(--dlab); text-shadow:none; }
.deck .k:first-child .dv { font-size:112px; line-height:.95; color:var(--hot); text-shadow:0 0 28px rgba(236,106,50,.35); }
.deck .dv.hot { color:var(--hot); text-shadow:0 0 22px rgba(236,106,50,.35); }
.deck .dv.ok { color:#8FD39A; text-shadow:0 0 22px rgba(88,184,104,.3); }
.deck .rd { color:var(--dlab); font:12px var(--f-mono); margin-top:8px; }
.sys { flex-basis:100%; margin:0 28px; padding-top:18px; border-top:1px dashed var(--deck-line); display:flex; flex-wrap:wrap; gap:10px 28px; align-items:center; color:var(--dlab); font:12.5px var(--f-mono); }
.sys b { color:var(--nixie); font-weight:500; }
.sys .ok { color:#8FD39A; } .sys .bad { color:var(--hot); }

.row { display:flex; flex-wrap:wrap; gap:20px; }
.f4 { flex:4 1 320px; } .f5 { flex:5 1 380px; } .f6 { flex:6 1 420px; } .f7 { flex:7 1 520px; } .f8 { flex:8 1 560px; } .f12 { flex:1 1 100%; }
.panel { position:relative; overflow:hidden; background:var(--panel); border:1px solid var(--line); border-radius:6px; padding:24px; min-width:0; animation:rise .45s both; }
@keyframes rise { from { opacity:0; transform:translateY(10px); } }
.panel.busy::after { content:""; position:absolute; top:0; left:0; height:3px; width:35%; background:var(--orange); animation:busy 1.1s ease-in-out infinite; }
@keyframes busy { from { transform:translateX(-100%); } to { transform:translateX(300%); } }
.ph { display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin-bottom:20px; }
.ph h2 { margin:0; font-size:17px; font-weight:700; }
.ph .sp { margin-left:auto; }
.ph .lg { display:flex; gap:14px; font-size:12.5px; color:var(--soft); }
.ph .lg i { display:inline-block; width:10px; height:10px; margin-right:5px; vertical-align:-1px; }
.empty { color:var(--soft); font-size:13px; padding:18px 0; text-align:center; font-family:var(--f-mono); }

.seg { display:inline-flex; border:1px solid var(--ink); border-radius:4px; overflow:hidden; }
.seg button { min-height:34px; padding:0 14px; border:0; background:transparent; color:var(--ink); font:13px var(--f-sans); cursor:pointer; }
.seg button + button { border-left:1px solid var(--line); }
.seg button.on { background:var(--ink); color:var(--panel); }
.chk { display:inline-flex; align-items:center; gap:6px; font-size:12.5px; color:var(--soft); cursor:pointer; }

.chips { display:flex; flex-wrap:wrap; gap:18px 28px; }
.chip { flex:1 1 120px; border-top:4px solid var(--mute); padding-top:10px; }
.chip .l { font-size:13px; color:var(--soft); }
.chip .v { font:600 40px/1.1 var(--f-num); font-stretch:condensed; }

/* 30 日健康条带：柱高 = 活跃设备，颜色 = 当天报错设备占比 */
.hb { display:flex; align-items:flex-end; gap:4px; height:170px; position:relative; }
.hb .b { flex:1; min-width:4px; height:100%; display:flex; align-items:flex-end; cursor:crosshair; position:relative; }
.hb .b span { width:100%; border-radius:2px 2px 0 0; background:var(--teal); transform-origin:bottom; animation:grow .7s cubic-bezier(.2,.8,.2,1) both; min-height:3px; }
.hb .b.warn span { background:var(--mustard); } .hb .b.bad span { background:var(--orange); } .hb .b.none span { background:var(--track); }
.hb .b:hover span { filter:brightness(1.2); box-shadow:0 0 12px color-mix(in srgb, var(--orange) 40%, transparent); }
@keyframes grow { from { transform:scaleY(0); } }
.xl { display:flex; justify-content:space-between; margin-top:8px; font:12px var(--f-mono); color:var(--soft); }
.vt { position:fixed; z-index:90; pointer-events:none; background:var(--panel); border:1px solid var(--ink); border-radius:4px; box-shadow:0 4px 14px rgba(0,0,0,.18); padding:10px 12px; font-size:12px; line-height:1.7; display:none; }
.vt b { font-family:var(--f-num); font-size:16px; }

.comp { display:flex; align-items:center; gap:12px; padding:14px 0; border-bottom:1px solid var(--line); }
.comp:last-child { border-bottom:0; }
.comp .dot { width:10px; height:10px; border-radius:50%; flex:none; box-shadow:0 0 0 3px var(--panel); }
.comp .dot.up { background:var(--live); box-shadow:0 0 8px rgba(88,184,104,.8); }
.comp .dot.idle { background:var(--mustard); } .comp .dot.none { background:var(--mute); }
.comp .n { font-weight:500; } .comp .d { font:12px var(--f-mono); color:var(--soft); }
.comp .s { margin-left:auto; font:12px var(--f-mono); color:var(--soft); text-align:right; }

.osbar { display:flex; gap:3px; height:56px; border-radius:4px; overflow:hidden; margin-bottom:12px; }
.osbar span { min-width:4px; transform-origin:left; animation:growx .7s cubic-bezier(.2,.8,.2,1) both; }
@keyframes growx { from { transform:scaleX(0); } }
.lrow { display:flex; align-items:center; gap:12px; padding:9px 0; border-bottom:1px solid var(--line); }
.lrow:last-child { border-bottom:0; }
.lrow i { width:12px; height:12px; flex:none; }
.lrow .n { font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.lrow .c { margin-left:auto; font:13px var(--f-mono); color:var(--soft); }
.lrow .p { width:70px; text-align:right; font:600 22px var(--f-num); font-stretch:condensed; }
.mrow { display:grid; grid-template-columns:minmax(90px,170px) 1fr 64px; gap:12px; align-items:center; padding:7px 0; }
.mrow .n { font:13px var(--f-mono); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.mrow .tr { height:10px; background:var(--track); }
.mrow .tr span { display:block; height:100%; background:var(--orange); transform-origin:left; animation:growx .7s cubic-bezier(.2,.8,.2,1) both; }
.mrow.t .tr span { background:var(--teal); }
.mrow .p { text-align:right; font:600 20px var(--f-num); font-stretch:condensed; }

.heat { display:grid; grid-template-columns:22px repeat(24, minmax(0,1fr)); gap:3px; }
.heat .c { height:30px; border-radius:1px; background:var(--teal); cursor:crosshair; transition:transform .1s; }
.heat .c:hover { transform:scale(1.18); outline:2px solid var(--ink); z-index:2; }
.heat .y { font:12px/30px var(--f-mono); color:var(--soft); }
.heat .x { font:12px var(--f-mono); color:var(--soft); }

.fun .f { display:grid; grid-template-columns:110px 1fr 120px; gap:14px; align-items:center; padding:10px 0; }
.fun .tr { height:14px; background:var(--track); }
.fun .tr span { display:block; height:100%; transform-origin:left; animation:growx .9s cubic-bezier(.2,.8,.2,1) both; }
.fun .p { text-align:right; } .fun .p b { font:600 24px var(--f-num); font-stretch:condensed; } .fun .p small { font:12px var(--f-mono); color:var(--soft); margin-left:6px; }
.pstats { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:12px; margin-bottom:14px; }
.pstat { border:1px solid var(--line); border-radius:5px; padding:10px 12px; background:var(--bg); }
.pstat .l { font-size:12px; color:var(--soft); }
.pstat .v { font:600 28px/1.15 var(--f-num); font-stretch:condensed; margin-top:2px; }
.pstat .v.bad { color:var(--red); } .pstat .v.warn { color:var(--orange); } .pstat .v.ok { color:var(--teal); }
.prow { display:flex; align-items:center; gap:12px; padding:10px 4px; border-bottom:1px solid var(--line); }
.prow:last-child { border-bottom:0; }
.prow .m { font:12px var(--f-mono); color:var(--soft); margin-top:3px; }
.prow .r { margin-left:auto; text-align:right; font:12px var(--f-mono); white-space:nowrap; }
@media (max-width:760px) { .pstats { grid-template-columns:repeat(2, minmax(0,1fr)); } }

/* 报错列表：首条带橙色左边线与渐隐底色 */
.cr { display:grid; grid-template-columns:44px 1fr auto; gap:14px; align-items:center; padding:14px 16px; border-bottom:1px solid var(--line); cursor:pointer; transition:background .15s; }
.cr:hover { background:var(--track); }
.cr.top { background:linear-gradient(90deg, color-mix(in srgb, var(--orange) 10%, transparent), transparent 70%); box-shadow:inset 3px 0 0 var(--orange); }
.cr.on { background:var(--track); box-shadow:inset 3px 0 0 var(--teal); }
.cr .rk { font:600 26px var(--f-num); color:var(--soft); text-align:center; }
.cr.top .rk { color:var(--orange); }
.cr .t { font-weight:700; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cr .m { font:12px var(--f-mono); color:var(--soft); margin-top:4px; }
.cr .tags { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:5px; }
.cr .num { text-align:right; } .cr .num b { font:600 28px/1 var(--f-num); font-stretch:condensed; } .cr .num small { display:block; font:11.5px var(--f-mono); color:var(--soft); }
.tag { display:inline-flex; align-items:center; height:22px; padding:0 8px; border-radius:3px; font:500 11.5px var(--f-mono); background:var(--track); color:var(--soft); border:1px solid var(--line); }
.tag.crash, .tag.critical { color:#fff; background:var(--red); border-color:var(--red); }
.tag.boot, .tag.warn { color:#231F1A; background:var(--mustard); border-color:var(--mustard); }
.tag.install { color:#fff; background:var(--teal); border-color:var(--teal); }
.tag.ok { color:#fff; background:var(--olive); border-color:var(--olive); }
.tag.debug { color:#fff; background:var(--plum); border-color:var(--plum); }
.tag.info { color:var(--ink); }
.badge { display:inline-flex; height:28px; border-radius:4px; overflow:hidden; font:500 13px/28px var(--f-mono); margin:0 8px 8px 0; }
.badge span:first-child { background:#261E19; color:var(--nixie); padding:0 10px; }
.badge span + span { color:#fff; padding:0 10px; background:#1E5E62; }
.badge.o span + span { background:#B8431A; } .badge.y span + span { background:#8A6410; }

/* 深色终端代码块：脱敏现场、回复话术、清单编辑器 */
.term { background:var(--deck); color:var(--nixie); border-radius:6px; padding:14px 16px; font:12.5px/1.6 var(--f-mono); white-space:pre-wrap; word-break:break-word; max-height:380px; overflow:auto; margin:10px 0 0; box-shadow:inset 0 0 0 1px var(--deck-line); }
textarea.term { width:100%; min-height:440px; border:0; resize:vertical; max-height:none; }
textarea.term:focus { box-shadow:inset 0 0 0 1px var(--orange); }
.codebar { background:var(--deck); border-radius:6px; padding:14px 16px; display:flex; flex-wrap:wrap; align-items:center; gap:12px; box-shadow:inset 0 0 0 1px var(--deck-line); }
.codebar input { flex:1 1 260px; background:transparent; border:0; color:var(--nixie); font:600 34px var(--f-mono); letter-spacing:.14em; padding:4px 0; box-shadow:none; text-transform:uppercase; }
.codebar input::placeholder { color:rgba(243,217,164,.25); }
.codebar .btn { color:var(--nixie); border-color:var(--dlab); }
.codebar .btn:hover { background:var(--nixie); color:var(--deck); }
.codebar .btn.pri { color:#fff; border-color:var(--hot); background:var(--hot); }
.sample { border:1px solid var(--line); border-radius:6px; padding:14px 16px; margin-top:14px; }
.kv { display:flex; flex-wrap:wrap; gap:6px 18px; font:12px var(--f-mono); color:var(--soft); }
.kv b { color:var(--ink); font-weight:500; }
details summary { cursor:pointer; font-weight:500; margin-top:12px; color:var(--soft); }
details summary:hover { color:var(--orange); }
.toolbar { display:flex; flex-wrap:wrap; gap:10px; align-items:center; }
.view { display:none; flex-direction:column; gap:20px; }
.view.on { display:flex; }

.toast { position:fixed; right:24px; bottom:24px; z-index:99; display:flex; flex-direction:column; gap:10px; }
.toast div { background:var(--deck); color:var(--nixie); border-left:4px solid var(--teal); padding:12px 16px; border-radius:4px; font:13px var(--f-mono); box-shadow:0 8px 24px rgba(0,0,0,.3); animation:tin .3s both; max-width:420px; }
.toast div.err { border-left-color:var(--hot); }
@keyframes tin { from { opacity:0; transform:translateX(30px); } }

/* 登录闸门：口令校验通过前页面上只有这一块，控制台标记与脚本都不会下发 */
.gate { min-height:calc(100vh - 18px); display:flex; align-items:center; justify-content:center; padding:24px; position:relative; overflow:hidden; }
.gate::before { content:""; position:absolute; inset:-50%; background-image:linear-gradient(var(--line) 1px, transparent 1px), linear-gradient(90deg, var(--line) 1px, transparent 1px); background-size:48px 48px; transform:perspective(600px) rotateX(58deg); transform-origin:center 30%; opacity:.45; animation:floor 14s linear infinite; mask-image:radial-gradient(ellipse at center, #000 20%, transparent 70%); -webkit-mask-image:radial-gradient(ellipse at center, #000 20%, transparent 70%); }
@keyframes floor { to { background-position:0 480px, 0 0; } }
.gbox { position:relative; width:min(560px, 100%); background:var(--deck); border-radius:10px; padding:38px 36px 30px; box-shadow:inset 0 3px 0 rgba(0,0,0,.45), inset 0 0 0 1px var(--deck-line), 0 30px 80px rgba(0,0,0,.45); color:var(--nixie); overflow:hidden; }
.gbox::before { content:""; position:absolute; inset:0; pointer-events:none; background:repeating-linear-gradient(0deg, rgba(255,255,255,.03) 0 1px, transparent 1px 3px); }
.gbox .sig { display:flex; gap:4px; margin-bottom:26px; } .gbox .sig i { height:6px; flex:1; } 
.gbox h1 { margin:0; font:700 54px/1 var(--f-num); font-stretch:condensed; letter-spacing:.02em; }
.gbox h1 em { font-style:normal; color:var(--hot); text-shadow:0 0 24px rgba(236,106,50,.45); }
.gbox .sub { color:var(--dlab); letter-spacing:.18em; font-size:13px; margin-top:8px; }
.gbox .lock { display:flex; align-items:center; gap:10px; margin:28px 0 12px; color:var(--dlab); font:12.5px var(--f-mono); }
.gbox .lock i { width:8px; height:8px; border-radius:50%; background:var(--hot); box-shadow:0 0 10px rgba(236,106,50,.9); animation:pulse 1.2s infinite; }
.gfield { display:flex; align-items:center; gap:10px; border-bottom:2px solid var(--deck-line); padding:6px 0; transition:border-color .2s; }
.gfield:focus-within { border-color:var(--hot); }
.gfield span { font:600 22px var(--f-mono); color:var(--hot); }
.gfield input { flex:1; background:transparent; border:0; box-shadow:none; color:var(--nixie); font:500 22px var(--f-mono); letter-spacing:.2em; padding:6px 0; }
.gfield input:focus { box-shadow:none; }
.gbox .go { width:100%; margin-top:22px; min-height:50px; background:var(--hot); border:0; color:#fff; font:700 16px var(--f-sans); letter-spacing:.3em; border-radius:4px; cursor:pointer; position:relative; overflow:hidden; }
.gbox .go::after { content:""; position:absolute; top:0; bottom:0; width:40%; background:linear-gradient(90deg, transparent, rgba(255,255,255,.35), transparent); animation:sweep 2.4s linear infinite; }
.gbox .go:disabled { filter:grayscale(.6) brightness(.7); cursor:wait; }
.gbox .msg { min-height:20px; margin-top:14px; font:12.5px var(--f-mono); color:var(--hot); }
.gbox .log { margin-top:6px; font:12.5px/1.8 var(--f-mono); color:var(--dlab); min-height:0; }
.gbox .log div { animation:tin .25s both; } .gbox .log b { color:#8FD39A; font-weight:500; }
.gbox .foot { margin-top:18px; display:flex; justify-content:space-between; color:rgba(185,166,138,.6); font:11.5px var(--f-mono); }
.cursor { display:inline-block; width:9px; height:1.1em; background:var(--hot); vertical-align:-3px; margin-left:4px; animation:blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity:0; } }
.shake { animation:shake .42s both; }
@keyframes shake { 20%, 60% { transform:translateX(-10px); } 40%, 80% { transform:translateX(10px); } }
.fade-out { animation:fout .5s both; }
@keyframes fout { to { opacity:0; transform:scale(.97); filter:blur(4px); } }

/* 多用户：身份徽标、角色色标、弹窗、成员与审计列表；hidden 必须压过各组件自带的 display */
[hidden] { display:none !important; }
.gfield + .gfield { margin-top:12px; }
.gfield span:first-child { width:22px; }
.who { display:flex; align-items:center; gap:8px; font-size:13px; }
.who b { font-weight:700; }
.tag.role-support { color:#fff; background:var(--teal); border-color:var(--teal); }
.tag.role-maintainer { color:#231F1A; background:var(--mustard); border-color:var(--mustard); }
.tag.role-owner { color:#fff; background:var(--orange); border-color:var(--orange); }
.modal { position:fixed; inset:0; z-index:95; background:rgba(13,9,8,.62); backdrop-filter:blur(3px); display:flex; align-items:center; justify-content:center; padding:20px; animation:rise .2s both; }
.mbox { position:relative; overflow:hidden; width:min(480px,100%); background:var(--panel); border:1px solid var(--ink); border-radius:6px; padding:30px 26px 22px; box-shadow:0 30px 80px rgba(0,0,0,.45); }
.mbox::before { content:""; position:absolute; top:0; left:0; right:0; height:6px; background:linear-gradient(90deg, var(--orange) 0 33%, var(--mustard) 33% 66%, var(--teal) 66%); }
.fld { display:grid; grid-template-columns:80px 1fr; align-items:center; gap:12px; margin-bottom:12px; }
.fld > span { font-size:13px; color:var(--soft); }
.fld input, .fld select { width:100%; }
.fld input[type=checkbox] { width:auto; }
.fmsg { min-height:18px; font:12.5px var(--f-mono); color:var(--hot); margin:4px 0 10px; }
.roles { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px,1fr)); gap:16px; }
.rc { border-top:4px solid; padding-top:10px; }
.rc b { display:block; font-size:16px; margin-bottom:4px; }
.rc span { font-size:12.5px; color:var(--soft); }
.secret { margin-top:16px; }
.secret .codebar { margin:8px 0; }
.secret code { flex:1; color:var(--nixie); font:600 22px var(--f-mono); letter-spacing:.08em; word-break:break-all; }
.trow { display:grid; grid-template-columns:40px minmax(0,1.6fr) 130px 120px auto; gap:14px; align-items:center; padding:12px 4px; border-bottom:1px solid var(--line); }
.trow:last-child { border-bottom:0; }
.trow.off { opacity:.55; }
.trow .av { width:36px; height:36px; border-radius:4px; background:var(--teal); color:#fff; display:flex; align-items:center; justify-content:center; font:700 18px var(--f-num); }
.trow .t { font-weight:700; display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
.trow .m, .arow .m { font:12px var(--f-mono); color:var(--soft); }
.trow select { padding:6px 8px; }
.arow { display:grid; grid-template-columns:150px 180px 110px minmax(0,1fr) minmax(0,1.2fr); gap:12px; align-items:center; padding:10px 4px; border-bottom:1px solid var(--line); font-size:13px; }
.arow.bad { box-shadow:inset 3px 0 0 var(--red); background:linear-gradient(90deg, color-mix(in srgb, var(--red) 8%, transparent), transparent 60%); }
.arow .act { font-weight:700; }
.arow .mono { font:12px var(--f-mono); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
@media (max-width:760px) { .trow { grid-template-columns:36px 1fr; } .trow > :nth-child(n+3) { grid-column:2; } .arow { grid-template-columns:1fr; gap:2px; } }
/* 所有者有 5 个分页：中等宽度下收紧分页按钮、藏掉副标题和同步文字，让页头保持一行 */
@media (max-width:1480px) { .hd { gap:14px 24px; } nav { gap:6px; } nav a { padding:0 16px 0 12px; } .brand span, #syncAt, #clock { display:none; } .hd-r { gap:10px; } }

/* 清单可视化编辑器：左列表 / 中表单 / 右手机预览 */
.mf-ui { display:grid; grid-template-columns:270px minmax(0,1fr) 380px; gap:20px; align-items:start; }
.mf-prev { position:sticky; top:32px; }
.mf-list { padding:16px; }
.mf-sec { display:flex; justify-content:space-between; font:700 13px var(--f-sans); color:var(--soft); margin:6px 4px 8px; letter-spacing:.06em; }
.mf-sec span { font-family:var(--f-mono); }
.mf-item { display:flex; gap:10px; align-items:flex-start; padding:10px; border-radius:4px; cursor:pointer; border:1px solid transparent; transition:background .15s; }
.mf-item:hover { background:var(--track); }
.mf-item.on { background:var(--track); border-color:var(--line); box-shadow:inset 3px 0 0 var(--orange); }
.mf-item i { width:9px; height:9px; border-radius:50%; margin-top:6px; flex:none; }
.mf-item .t { font-weight:700; font-size:13.5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.mf-item .m { font:11.5px var(--f-mono); }
.mf-tip { margin-top:14px; font-size:12px; color:var(--soft); border-top:1px dashed var(--line); padding-top:10px; }
.mf-form .fld { grid-template-columns:88px 1fr; align-items:start; }
.mf-form .fld > span { padding-top:9px; }
.mf-form textarea, .mf-form input:not([type=checkbox]), .mf-form select { width:100%; }
.mf-form textarea { resize:vertical; font-family:inherit; line-height:1.55; }
.mf-form .mono { font-family:var(--f-mono); }
.fh { font-size:12px; color:var(--soft); margin-top:5px; }
.mf-quick { display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
.btn.on { background:var(--ink); color:var(--panel); }
.mf-pair { display:flex; gap:8px; align-items:center; }
.mf-sub { margin:20px 0 12px; padding-top:14px; border-top:1px dashed var(--line); font-weight:700; }
.mf-sub span { font-weight:400; font-size:12px; color:var(--soft); margin-left:8px; }
.mf-steps > div { display:flex; flex-wrap:wrap; gap:5px; align-items:center; margin-bottom:8px; }
.mf-steps em { font-style:normal; font-size:12px; color:var(--soft); width:72px; }
.mf-steps .btn { font-family:var(--f-mono); font-weight:500; min-height:28px; padding:0 8px; }
.mf-issues { margin-bottom:14px; display:flex; flex-direction:column; gap:4px; }
.mf-issues div { font:12.5px var(--f-mono); padding:6px 10px; border-radius:3px; }
.mf-issues .err { color:#fff; background:var(--red); }
.mf-issues .warn { color:#231F1A; background:var(--mustard); }
.mf-sim { display:grid; grid-template-columns:1fr 1fr; gap:8px; align-items:center; }
.mf-sim input, .mf-sim select { width:100%; }
.mf-sim input[type=checkbox] { width:auto; }
.mf-sim .chk { white-space:nowrap; }
.mf-out { margin-top:10px; padding:10px 12px; border-radius:4px; font:12.5px var(--f-mono); background:var(--track); }
.mf-out.yes { color:#fff; background:var(--olive); }
.mf-out.no { color:#fff; background:var(--red); }
.mf-out.warn { color:#231F1A; background:var(--mustard); }
#pvSim textarea, #pvSim > select { width:100%; margin-top:8px; }
.pv-phone { width:300px; margin:0 auto; border-radius:34px; padding:10px; background:#050506; box-shadow:0 0 0 2px #2a2b30, 0 24px 60px rgba(0,0,0,.45); }
.pv-status { display:flex; justify-content:space-between; padding:6px 18px 4px; color:#fff; font:600 12px var(--f-sans); }
.pv-sig { font-size:11px; letter-spacing:.04em; }
.pv-screen { position:relative; overflow:hidden; height:560px; border-radius:24px; background:#0B0C0E; padding:16px 14px; color:#fff; font:14px/1.45 var(--f-sans); }
.pv-title { font:700 26px var(--f-sans); margin:8px 2px 16px; }
.pv-banner { background:#1A1B1F; border-radius:12px; padding:12px 8px 12px 14px; margin-bottom:12px; }
.pv-bh { display:flex; align-items:center; gap:8px; }
.pv-bh i { width:8px; height:8px; border-radius:50%; flex:none; }
.pv-bh b { flex:1; font-size:15px; line-height:20px; }
.pv-x { color:#8E929B; font-size:12px; padding:0 6px; }
.pv-body { color:#8E929B; font-size:13.5px; line-height:19px; margin-top:6px; white-space:pre-wrap; word-break:break-word; }
.pv-chip { display:inline-block; margin-top:8px; color:#E97F0F; background:rgba(233,127,15,.16); border-radius:6px; padding:5px 10px; font:700 12px var(--f-sans); }
.pv-row { display:flex; align-items:center; gap:12px; background:#1A1B1F; border-radius:12px; padding:14px; margin-bottom:8px; }
.pv-row i { width:26px; height:26px; border-radius:8px; background:#262830; flex:none; }
.pv-row span { height:10px; border-radius:5px; background:#262830; }
.pv-dim { position:absolute; inset:0; background:rgba(0,0,0,.55); display:flex; align-items:center; justify-content:center; padding:20px; }
.pv-dialog { background:#1A1B1F; border-radius:24px; padding:22px 20px 16px; width:100%; max-height:80%; overflow:auto; }
.pv-dialog b { font-size:18px; line-height:22px; display:block; }
.pv-btns { display:flex; justify-content:flex-end; gap:8px; margin-top:18px; }
.pv-btn { background:#E97F0F; color:#fff; border-radius:20px; padding:8px 16px; font:700 13px var(--f-sans); }
.pv-btn.sec { background:#262830; }
.pv-shade { position:absolute; inset:0; padding:20px 12px; background:linear-gradient(160deg, #20242c, #0b0c0e 70%); }
.pv-clock { font:300 52px var(--f-sans); text-align:center; }
.pv-date { text-align:center; color:#b9bcc4; font-size:13px; margin-bottom:22px; }
.pv-noti { background:rgba(40,42,48,.92); border-radius:20px; padding:14px 16px; margin-bottom:8px; }
.pv-noti b { display:block; font-size:14.5px; margin-top:6px; }
.pv-noti .pv-body { color:#c9ccd3; margin-top:2px; }
.pv-noti.ghost { height:58px; opacity:.4; }
.pv-nh { display:flex; align-items:center; gap:6px; font-size:11.5px; color:#b9bcc4; }
.pv-ico { width:16px; height:16px; border-radius:50%; background:#E97F0F; color:#fff; font:700 10px/16px var(--f-sans); text-align:center; }
.pv-fail { background:#1A1B1F; border-radius:12px; padding:14px; margin-bottom:12px; }
.pv-fh { font-weight:700; margin-bottom:8px; }
.pv-code { font:700 12px var(--f-mono); color:#E8A064; }
.pv-cause { font-weight:700; font-size:13.5px; margin-top:8px; }
.pv-nbs { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
.pv-nb { border-radius:10px; padding:7px 12px; font:700 12px var(--f-sans); background:#262830; color:#fff; }
.pv-nb.hi { background:rgba(233,127,15,.16); color:#E97F0F; }
.pv-links { display:flex; align-items:center; gap:6px; margin-top:10px; font:600 12px var(--f-sans); color:#E97F0F; }
.pv-dot { color:#555966; }
/* 中等宽度：条目列表挪到上方整行，表单与预览并排，保证改字时预览始终在视线内 */
@media (max-width:1360px) { .mf-ui { grid-template-columns:minmax(0,1fr) 350px; } .mf-list { grid-column:1 / -1; display:grid; grid-template-columns:repeat(auto-fill, minmax(220px,1fr)); gap:4px 10px; } .mf-list .mf-sec, .mf-list .mf-tip, .mf-list .empty { grid-column:1 / -1; } .mf-prev { padding:20px 16px; } }
@media (max-width:860px) { .mf-ui { grid-template-columns:1fr; } .mf-prev { position:static; } }
@media (max-width:900px) { .deck .k:first-child .dv { font-size:84px; } .deck .dv { font-size:52px; } .brand b { font-size:36px; } .hd-r { margin-left:0; } }
@media (max-width:760px) { .wrap { padding:0 14px 40px; } nav a { padding:0 14px 0 10px; min-height:42px; } .deck .k { padding:0 18px; } .heat { gap:2px; } .heat .c { height:20px; } .fun .f { grid-template-columns:80px 1fr 90px; } .cr { grid-template-columns:30px 1fr; } .cr .num { grid-column:2; text-align:left; } }
@media (prefers-reduced-motion:reduce) { *, *::before, *::after { animation:none !important; transition:none !important; } }
`;
