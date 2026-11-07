# 消息平台接入

MaiBot 通过插件（适配器）连接聊天平台。MaiDroid 的“消息平台”页负责把这些适配器装进容器里的 MaiBot、填好配置、展示连接状态。目前接入了 QQ 的三种方式：

| 接入方式 | 原理 | 代码 |
| --- | --- | --- |
| QQ 官方机器人 | MaiBot-QQ-Adapter 插件直连 QQ 开放平台，需要在 q.qq.com 申请 AppID、AppSecret | `platform/qqofficial/`、`ui/platform/QqOfficialConfigDialog.kt` |
| NapCat | 容器里跑 LinuxQQ + NapCat，扫码登录个人 QQ，NapCat 提供 OneBot11 WebSocket，MaiBot-SnowLuma-Adapter 插件去连 | `platform/napcat/`、`platform/qq/`、`ui/platform/NapCatDialogs.kt` |
| SnowLuma 客户端 | 容器里跑 LinuxQQ，SnowLuma 注入进去，在 QQ 窗口里扫码登录个人 QQ，SnowLuma 提供 OneBot11 WebSocket，同样由 MaiBot-SnowLuma-Adapter 去连。和 NapCat 二选一 | `platform/snowluma/`、`platform/qq/`、`ui/platform/SnowLumaDialogs.kt` |

三种方式在 MaiBot 侧都是装一个适配器插件（NapCat 和 SnowLuma 客户端共用同一个），装好或改了配置后都要重启 MaiBot 才生效，统一经 `MaiBotRuntimeService.start(context, ACTION_RESTART_RUNTIME)`。

## 术语

这几个名字长得像，代码、注释和文档里按下表用，不要混：

| 说法 | 指的是 | 代码里 |
| --- | --- | --- |
| 适配器 | MaiBot 的插件，把某个聊天平台的消息转成 MaiBot 能处理的格式。装在容器里 MaiBot 的 `plugins/` 下 | 各平台包里的 `*Adapter*` |
| SnowLuma 适配器 | MaiBot-SnowLuma-Adapter，NapCat 和 SnowLuma 客户端两种接入方式共用的适配器。它同时支持这两种 QQ 协议端，MaiDroid 让它连本机 `127.0.0.1:8095`，连上后它自己认出对端是哪种。界面和终端日志里对用户说的“QQ 适配器”就是它 | `platform/qq/SnowLumaAdapter*` |
| 旧 NapCat 适配器 | MaiBot-Napcat-Adapter，已停更。早期版本的 MaiDroid 装的是它，启动 NapCat 前会自动换成 SnowLuma 适配器 | `SnowLumaAdapterScripts.LEGACY_*` |
| QQ 官方机器人适配器 | MaiBot-QQ-Adapter，官方机器人接入方式用的适配器，直连 QQ 开放平台。注意它的仓库名里也有“QQ-Adapter”，但界面上的“QQ 适配器”不是它 | `platform/qqofficial/QqOfficialAdapter*` |
| QQ 协议端 | 在容器里登录个人 QQ、对外提供 OneBot 服务的程序，有 NapCat 和 SnowLuma 客户端两种，同一时刻只启用一种 | `platform/qq/QqClient.kt` |
| NapCat | QQ 协议端：容器里跑的 LinuxQQ + NapCat，扫码登录个人 QQ，对外提供 OneBot 服务 | `platform/napcat/NapCat*` |
| SnowLuma 客户端 | 另一种 QQ 协议端：容器里跑原版 LinuxQQ，SnowLuma 注入进去，对外提供 OneBot 服务。界面上的“SnowLuma”选项就是它，和 SnowLuma 适配器不是一回事 | `platform/snowluma/`、`AccessMethod.SnowLumaClient` |
| 接入方式 | 消息平台页选择器上的选项（官方机器人 / NapCat / SnowLuma），说的是消息从哪条路进出 QQ，不是适配器 | `ui/platform` 的 `AccessMethod`、`AccessMethodSelector` |

## 消息平台页的结构

- `PlatformCatalog.kt`：“发现”区展示哪些平台（`AvailablePlatforms`），每个平台可选的接入方式（`AccessMethod`），以及“已安装”行按真实状态生成卡片的规则（`installedPlatformCards`）。设置页的“运行中平台数”也按这里统计。
- `MessagePlatformsPage.kt`：页面本身，按窗口大小选排版（`messagePlatformsLayout`）：手机竖屏是单列分组列表，手机横屏分左右两栏，平板是卡片网格；还持有各弹窗的开关（`rememberSaveable`，用户切去 QQ 扫码导致 Activity 重建后弹窗还在）。
- `PlatformRows.kt`：手机用的分组列表，已连接行、发现行和分类小胶囊，样子和设置页一致。
- `PlatformCards.kt`：平板用的已安装小卡片、平台安装卡片。右侧按钮按选中的接入方式显示下载 / 进度环 / 设置，这个状态和点击逻辑（`platformInstallAction`）卡片和列表行共用。
- `AccessMethodSelector.kt`：接入方式选择器。
- `NapCatDialogs.kt`、`SnowLumaDialogs.kt`、`QqOfficialConfigDialog.kt`：三种接入方式各自的弹窗。

Discord、WhatsApp、Signal 目前只是展示：平板卡片上点下载会提示“暂未适配”，手机列表里没适配的平台直接标“暂未适配”，不放下载按钮。QQ 的“SnowLuma”选项是 SnowLuma 客户端，和 NapCat 选项装的 SnowLuma 适配器不是一回事，见上面的术语表。

## QQ 官方机器人

### 安装与配置（`QqOfficialPlatformManager`）

点下载打开凭据弹窗，填好 AppID、AppSecret 后：

1. 检查容器和 MaiBot 源码。
2. 已经装过（包括用户从 MaiBot WebUI 插件市场装的副本）就跳过下载；否则经 GitHub 镜像把 MaiBot-QQ-Adapter 克隆到 `plugins/` 下以点开头的临时目录，再改名为 `plugins/MaiBot-QQ-Adapter`。点开头的目录 MaiBot 扫插件时会跳过，不会加载克隆了一半的插件。
3. 宿主侧直接改写插件的 `config.toml`（先写临时文件再改名），AppSecret 不经过命令行，不会出现在进程参数和日志里。
4. 重启 MaiBot。

已安装后点齿轮打开同一个弹窗，凭据没变就不重启。

“是否已安装”按清单 ID 判断（`QqOfficialAdapterFiles` -> `maibot/MaiBotPlugins.findPluginDirById`）：插件市场装的目录名是 `Mai-with-u_qq-adapter`，只认固定目录名会再装一份，同一插件 ID 出现两份会冲突。

### config.toml 读写（`QqOfficialAdapterConfig`）

只读写三项：`[plugin].enabled`、`[qq_official].app_id`、`[qq_official].app_secret`，其余内容（用户在 MaiBot WebUI 里改过的禁言、撤回等配置、注释）原样保留。逐行读写交给 `maibot/PluginConfigToml`，它不是通用 TOML 解析器，只认 MaiBot 写回配置时用的单行写法。

### 连接状态怎么来的

适配器连上 QQ 网关后不打任何日志，只在失败时每隔几秒打一行“QQ 官方网关连接失败，将重试: 原因”。所以：

- `QqOfficialConnectionErrors` 从 `TerminalLog` 的每一行里识别这类报错，翻译成用户看得懂的说明（例如 IP 不在白名单、凭据错误）。
- 一分钟内没有新报错就认为已连上（`QqOfficialErrorStaleMs`）；MaiBot 重新启动（日志里出现 `MaiBotProcessSupervisor.PROCESS_STARTED_LOG`）时清掉旧报错。
- 卡片上的“已启用”= 凭据齐全 + 插件启用 + MaiBot 在跑 + 近期没有连接报错，是推断值，不是适配器上报的。

群聊里默认要 @机器人 才会收到消息，这是 QQ 开放平台的规则。

## NapCat

### 安装（`NapCatPlatformManager.installNapCat`、`NapCatScripts.install`、`SnowLumaAdapterInstaller`）

1. 检查容器、MaiBot 源码和 MaiBot 版本：SnowLuma 适配器对 MaiBot 有最低版本要求（`SnowLumaAdapterScripts.MIN_MAIBOT_VERSION`），MaiBot 会直接拒绝加载版本范围外的插件，所以版本不够时提示先去设置页更新 MaiBot。版本号读自 `pyproject.toml` 的 `[project].version`。
2. NapCat 本体还没装（`napcat_ready` 标记或 `onebot11.json` 缺一个）才运行安装脚本，进度通过容器 `$TMPDIR/progress`、`progress_des` 两个文件汇报，每 500 毫秒读一次显示在卡片的进度环上：
   - 生成 OneBot 访问 token（只在第一次安装时生成，存在 `/root/.maidroid/napcat_token`，重装沿用）；
   - 按 `GitHubMirrors` 的线路依次下载 NapCat 官方的 Linux 安装器，下到的必须是提到 napcat 的 shell 脚本（镜像限流、拉黑时会返回 200 的网页或报错文字），再运行它；
   - 写 `/root/napcat/config/onebot11.json`：在 `127.0.0.1:8095` 开 WebSocket 服务端，带上 token；
   - 写 `/root/.maidroid/napcat_ready` 标记，生成 `/root/launcher.sh`。
3. 装 MaiBot 侧的 SnowLuma 适配器（`SnowLumaAdapterScripts.install`）：没装过（按清单 ID `maibot-team.snowluma-adapter` 找，插件市场装的副本也算）就经 GitHub 镜像把 MaiBot-SnowLuma-Adapter 克隆到 `plugins/` 下以点开头的临时目录，再改名为 `plugins/MaiBot-SnowLuma-Adapter`；然后删掉清单 ID 为 `maibot-team.napcat-adapter` 的旧适配器（两个适配器同时连 NapCat 会把每条消息收两遍）。删之前先把旧适配器的 `config.toml` 读进内存，留给第 5 步搬设置。
4. 改用 NapCat（启用的是 SnowLuma 就先等它的 QQ 下线，见下面“和 NapCat 二选一”），经前台服务拉起 NapCat 并重启 MaiBot。整个目录移进 `plugins/` 时 MaiBot 不一定能热加载，所以要重启；重启时 MaiBot 发现插件没有 `config.toml`，会按适配器自己的默认值生成一份（带 MaiBot 强制要求的 `plugin.config_version`，默认 `enabled = false`，适配器先空闲着）。新适配器依赖 `silk-python`（语音转码），MaiBot 加载插件时自己用 uv 补装，有 aarch64 预编译包。
5. 等配置生成后迁移（`SnowLumaAdapterInstaller.migrateConfigWhenReady` -> `SnowLumaAdapterConfig.migrate`）：每 2 秒看一次，最多等 5 分钟；看到带 `config_version` 的配置后再等 5 秒（MaiBot 写完配置才订阅它的变化，改早了会错过热重载），然后在生成的配置上改：`[plugin].enabled = true`，`[client]` 的 `server`、`port`、`token` 写成 `127.0.0.1`、8095 和上面的 token，再把旧适配器里有对应项的设置搬过来（见下）。其余内容原样保留。MaiBot 监听插件配置文件，发现启用了会补装依赖并热启用适配器，不用再重启。

MaiDroid 不自己从头写这份配置：MaiBot 要求插件配置必须带 `plugin.config_version`，版本号和各段默认值都以适配器当前版本为准，由 MaiBot 生成最稳。“迁移过没有”只看 `[client]` 是否指向本机 NapCat（`SnowLumaAdapterConfig.isMigrated`），不看 `enabled`：用户之后在 WebUI 里关掉适配器，MaiDroid 不会每次启动都给打开。

安装器自己下载 NapCat 和 LinuxQQ 时用它自带的镜像列表，MaiDroid 只管拿到安装器本身。

### 旧适配器迁移

早期版本的 MaiDroid 装的是 MaiBot-Napcat-Adapter，它已经停更（清单名写着“不再更新，请使用Snowluma适配器”）。`NapCatProcessSupervisor`、`SnowLumaProcessSupervisor` 每次启动 QQ 协议端前经 `QqAdapterUpkeep` 检查一次：还没有新适配器或旧的还在，就按上面第 3、4 步换装并重启 MaiBot，再在后台等配置生成后迁移（第 5 步），不耽误 QQ 启动。只差配置迁移的（上次等配置时应用被杀或超时）直接在后台等着迁移。

- MaiBot 低于适配器要求的最低版本时先不动，旧适配器继续用，终端日志提示去更新 MaiBot；更新完下次启动 QQ 协议端时再换。
- 下载失败时旧适配器还在（先装新的、再删旧的），下次启动 QQ 协议端再试。
- 旧配置里有对应项的设置原样搬到新配置：`[plugin].enable_private_chat_tool` -> `[chat_abilities]`，`[chat].ban_user_id`、`ban_qq_bot` -> `[filters]`，`[filters].ignore_self_message` -> `[debug]`，`[filters]` 的四个 `regex_filter_*` 和 `[notice].enabled` 原位搬。只搬写在一行内的值（`PluginConfigToml.isCompleteValue`），多行数组搬过来会写坏文件。旧的群聊、私聊名单新适配器没有对应功能，不搬（新适配器默认全部接收）；连接项以 MaiDroid 写的为准。
- 旧配置只在内存里留到迁移完成，应用中途被杀就只迁连接项。

### 运行（`NapCatProcessSupervisor`）

每次启动：需要时换装 SnowLuma 适配器（见上）-> 清掉残留的 Xvfb、qq、launcher 进程和 X11 锁 -> 重新生成 `launcher.sh` -> 前台运行它（起一个 1x1 的 Xvfb，再用 `LD_PRELOAD` 注入 NapCat 启动 LinuxQQ）。显示号不写死：手机上各个应用共用抽象 Unix 套接字，别的应用（另一个 MaiDroid、Termux 里的 VNC）占着 `:1` 时这边的 Xvfb 起不来，QQ 还会连到别人的显示上、一直出不了二维码。所以用 `Xvfb -displayfd` 让它自己挑第一个空着的号，记在 `/tmp/.maidroid-display`，`cleanup` 时一起删掉。输出加 `[NapCat]` 前缀写进终端日志。NapCat 退出后不自动重启。NapCat 本体装好、而且启用的是 NapCat 才启动，不看适配器装没装。

### 状态怎么来的（`NapCatStatusReader`、`NapCatWebUiClient`）

两种来源：

- **文件和端口**（每 2 秒一次）：是否装好、`cache/qrcode.png` 的路径和修改时间、OneBot 配置、QQ 号、OneBot 端口能不能连上。OneBot 服务端只有 QQ 登录后才监听，所以“端口能连上”就等于“QQ 在线、MaiBot 能连”。
- **NapCat 自带的 WebUI 接口**（`127.0.0.1:6099`，端口和 token 在 `config/webui.json`）：查扫码进度、当前登录账号，让 NapCat 重新出二维码，清除自动登录账号。登录接口要提交 `sha256(token + ".napcat")` 换取凭证，每分钟限 10 次，所以凭证缓存 50 分钟复用。

端口第一次连通时问一次当前账号，按 QQ 号挑 `onebot11_<QQ号>.json` 显示配置和昵称；换过号后“最近修改的配置文件”不一定是当前账号，所以不能只按修改时间猜。

### 登录二维码

点 NapCat 设置时先问 NapCat QQ 是否已登录：已登录打开 NapCat 管理页（`/webui/?token=…`，结尾斜杠不能少，否则前端路由不渲染，整页白屏），未登录弹二维码。

二维码弹窗打开期间每 3 秒问一次扫码进度（`keepQrCodeFresh`），按 `NapCatQrCodeRefresh` 决定要不要替用户换码：

- QQ 的二维码约 120 秒失效。NapCat 只在 QQ 回 ErrCode 3 时自己重新出码，遇到 ErrCode 10 会一直停在旧码上，所以码龄超过 125 秒（多留 5 秒给 NapCat 先自己换）就主动请求新码。
- 已扫码、等用户在手机上确认时，再多宽限 60 秒才视为作废，避免打断正在确认的用户。
- 已登录、登录后初始化中、登录服务重连中、正在出码时不动。
- 换码失败后至少隔 15 秒再自动重试。

扫码完成（NapCat 报 ready 或 OneBot 端口能连上）后弹窗自动关闭。

### 退出 QQ 登录

NapCat 没有注销 QQ 的接口，QQ 只随进程结束下线。所以“退出 QQ 登录”是：清除 NapCat 的自动登录账号（否则重启后又自动登录回去）-> 删掉旧二维码图 -> 只重启 NapCat，不动 MaiBot。之后打开二维码弹窗会自动要一张新码，可以换号扫码。

## SnowLuma 客户端

SnowLuma 客户端（[SnowLuma/SnowLuma](https://github.com/SnowLuma/SnowLuma)）是另一种 QQ 协议端。它不自己登录 QQ：容器里先在 Xvfb 上跑一个原版 LinuxQQ，SnowLuma 用 ptrace 把自己的原生组件注入 QQ 主进程，借 QQ 本身收发消息，再对外提供 OneBot11 WebSocket。

SnowLuma 的原生组件是闭源的专有软件，只能用于非商业用途；它的用户协议规定，并入第三方安装包或用自动化脚本部署都要作者书面授权。MaiDroid 取得了这份授权，在安装时从官方 Release 自动下载发行包，不随 APK 分发。安装前先弹协议弹窗（`SnowLumaAgreementDialog`，附用户协议、隐私政策的链接），用户点“同意并安装”才开始装，同意记录写在 `/root/.maidroid/snowluma_consent`；有这份记录，启动时才通过 `SNOWLUMA_ACCEPT_EULA`、`SNOWLUMA_ACCEPT_PRIVACY` 替用户确认协议。

### 和 NapCat 二选一（`platform/qq/`）

NapCat 和 SnowLuma 都在容器里跑 LinuxQQ（共用 `/opt/QQ` 和 QQ 的登录数据），都在 `127.0.0.1:8095` 开 OneBot 服务端，用的是同一份 token（`/root/.maidroid/napcat_token`，见 `QqOneBot`）。所以：

- MaiBot 侧装的是同一个 SnowLuma 适配器，连接配置一样。适配器连上后自己认出对端是哪种，两种协议端之间切换不用重新迁移配置。
- 同一时刻只能启用一种，启用哪种记在容器的 `/root/.maidroid/qq_client`（`QqClientChoice`）。没有这个文件时按 NapCat 算，老版本只有 NapCat。前台服务拉起协议端时两个监管器各自看它，只有启用的那个会启动。
- 切换（`QqClientSwitcher.activate`）：先改记录，再停掉另一个、等它的 QQ 下线，然后经 `ACTION_RESTART_RUNTIME` 拉起这一个并重启 MaiBot。两边安装时都要跑 apt、装 QQ，共用一把锁（`QqClientSwitcher.installLock`），不能同时装。
- 消息平台页上，选中的接入方式装好了、但启用的是另一个时，点按钮先弹确认（`QqClientSwitchDialog`）再切换；选中的是没装的 NapCat 就直接装，装完自动改用它。停用的那个在“已连接”里写“已停用”，不算进运行中的平台数。

适配器的安装、配置迁移和旧适配器换装两边共用（`QqAdapterUpkeep`、`SnowLumaAdapterInstaller`），流程见上面 NapCat 一节的第 3~5 步和“旧适配器迁移”。

### 安装（`SnowLumaPlatformManager.installSnowLuma`、`SnowLumaScripts.install`）

1. 检查容器、MaiBot 源码和版本（同 NapCat），再看网络和剩余空间（约 1 GB）。
2. SnowLuma 本体还没装（`snowluma_ready` 标记、`index.mjs`、全局 OneBot 配置缺一个）才运行安装脚本，进度经 `/tmp/snowluma_progress`、`snowluma_progress_des` 汇报（和 NapCat 的进度文件分开）：
   - 沿用或生成 OneBot token；第一次安装时生成 SnowLuma 管理页（WebUI）的密码，存在 `/root/.maidroid/snowluma_webui_password`；记下协议同意。
   - apt 装 `xvfb`、`xdotool`（往 QQ 窗口里点击）、`procps`、`curl`、`ca-certificates`、`tar` 和中文字体 `fonts-wqy-zenhei`（不然 QQ 窗口里的字显示成方块）。
   - `/opt/QQ/resources/app/package.json` 里的 QQ 版本不是 SnowLuma 上游钉的那一版时，下载这一版的 deb 装上。同一版 QQ 有两种写法：安装包名里是“版本号_发布日期”（`SnowLumaScripts.QQ_VERSION`，`3.2.32_260812`），`package.json` 里是“版本号-构建号”（`QQ_PACKAGE_JSON_VERSION`，`3.2.32-52194`，真机上读出来的），比对用后者；NapCat 装过别的版本也换掉（允许降级）。先试 QQ 官方下载地址，再经 GitHub 镜像试上游安装脚本用的备份，下到的都要对得上 sha256。再往 `/etc/hosts` 里挡住 `qqpatch.gtimg.cn`，免得 QQ 热更新换掉组件、注入对不上。
   - 经 GitHub 镜像下载 SnowLuma 完整版发行包（自带 Node 24），校验 sha256 后解到 `/root/snowluma/app.tmp`，入口和 node 都在才换成 `/root/snowluma/app`。
   - 写全局 OneBot 配置 `/root/snowluma/data/config/onebot.json`：只开一个 `127.0.0.1:8095` 的 WebSocket 服务端，带上 token，消息用 array 格式。SnowLuma 默认的 HTTP 3000、WS 3001 不开；`#sl` 状态命令关掉，不然群里有人发 `#sl` 时机器人会自动回一条状态。
   - 写完成标记，生成启动脚本 `/root/snowluma-launcher.sh`。
3. 没装 SnowLuma 适配器就装（同 NapCat 第 3 步）。
4. 改用 SnowLuma（启用的是 NapCat 就先等它的 QQ 下线），经前台服务拉起 SnowLuma 并重启 MaiBot，等适配器配置生成后迁移（同 NapCat 第 5 步；以前为 NapCat 迁移过的不用再迁）。

失败时错误码以 `SL-` 开头。下载那两步失败时手机已经断网的，等网络恢复（最多 30 分钟）自动从头再装一次。

版本是钉死的：SnowLuma 的原生组件绑着 QQ 的版本。升级时 `SnowLumaScripts` 里 SnowLuma 和 QQ 两组常量（版本、地址、sha256）一起改，再到真机上验证注入。

### 运行（`SnowLumaProcessSupervisor`）

每次启动：需要时换装 SnowLuma 适配器 -> 清掉残留的 Xvfb、qq、SnowLuma、启动脚本进程和 X11 锁 -> 重新生成启动脚本 -> 挑 WebUI 端口（默认 5099，被别的程序占着就换一个空端口，经 `SNOWLUMA_WEBUI_PORT` 传进去，脚本记在 `/tmp/.maidroid-snowluma-webui-port`）-> 前台运行启动脚本：

1. 起 Xvfb，屏幕 1280x800。显示号用 `-displayfd` 自己挑（理由同 NapCat），记在 `/tmp/.maidroid-snowluma-display`。`-fbdir /tmp/snowluma-screen` 让 Xvfb 把屏幕内容一直写在一个 XWD 文件里，宿主侧读它显示 QQ 窗口。
2. 后台每 2 秒拼一次 `/proc/locks` 的替身（见下）。
3. 后台起 SnowLuma，工作目录是 `/root/snowluma/data`。SnowLuma 把 `config/`、`data/`、`logs/` 放在工作目录下，和程序目录分开，升级时换掉程序目录不丢登录和配置。环境变量：打开自动注入（`SNOWLUMA_HOOK_AUTOLOAD=1`），不查更新，WebUI 只听本机，首次启动用上面的密码建 WebUI 账号，日志最多 64 MB、留 3 天。QQ 里的 hook 和 SnowLuma 之间的 unix 套接字目录固定为 `/tmp/snowluma-hook`（`SNOWLUMA_HOOK_RUNTIME_DIR`）：不设时 SnowLuma 按 QQ 进程的真实 uid 拼目录，PRoot `-0` 下 QQ 看到的是 0，Android 上真实的是应用 uid，两边会对不上。
4. 前台起 LinuxQQ（参数和 NapCat 一样），SnowLuma 每 1.5 秒找一次 QQ 主进程，找到就注入。QQ 退出后停掉拼锁表的循环、请 SnowLuma 退出（最多等 15 秒），最后结束 Xvfb：它不退 proot 就不退，应用会一直以为 SnowLuma 还在跑。

SnowLuma 认 QQ 登录了哪个号，是读 `/proc/locks`，找 QQ 进程锁着哪个账号的 `nt_msg.db`。Android 不让普通应用读 `/proc/locks`，读不到时它会一直停在“等待登录”（日志里有 `account detection failed: EACCES ... /proc/locks`）。应用自己的进程能读 `/proc/<pid>/fdinfo/*`，里面带 `lock:` 的行就是这个文件上的锁，格式和 `/proc/locks` 的一行相同。所以 proot 把 `/proc/locks` 绑到容器里的普通文件 `/tmp/.maidroid-proc-locks`（`InContainerPaths.PROC_LOCKS_STAND_IN`，每条容器命令都绑，平时是空的），启动脚本每 2 秒用 QQ 进程 fdinfo 里的锁信息重写一遍，先写临时文件再改名，SnowLuma 读不到写了一半的表。

输出加 `[SnowLuma]` 前缀写进终端日志。和 NapCat 一样不自动重启；SnowLuma 本体装好、而且启用的是 SnowLuma 才启动，不看适配器装没装。

停止：先给 SnowLuma 的两个 node 进程（守护进程和工作进程）发 SIGTERM（node 起来后把命令行改成了 `node /root/snowluma/app/index.mjs`，所以只按入口文件认），请它关掉 OneBot、WebUI、写完日志再退出（最多等 20 秒）-> 再请 QQ 主进程正常下线退出（最多 20 秒）-> 清掉剩下的 Xvfb、屏幕文件和记下的显示号。安全退出、低电量暂停、手机关机都走这条路。

### 状态怎么来的（`SnowLumaPlatformManager.refreshStatus`、`SnowLumaWebUiClient`）

每 2 秒一次，只在启用 SnowLuma 时探测（启用 NapCat 时 8095 是 NapCat 开的，不能算到 SnowLuma 头上）：

- **OneBot 端口**：SnowLuma 只在 QQ 登录后才开服务端，端口能连上就是 QQ 在线、MaiBot 能连。
- **SnowLuma 管理页接口**：`POST /api/login` 用 WebUI 密码换会话 token。token 有效 24 小时，这边缓存 23 小时；连错 5 次会被锁 15 分钟，所以密码被拒后 5 分钟内不再试。`GET /api/processes` 列出 QQ 进程，`snowLumaHookPhase` 把它归成登录进度：没有 QQ 进程是 QQ 正在启动，`loaded` 是注入好了、等用户登录，`online` 或 `loggedIn` 是已登录，`disconnected`、`error`（带原因）照实说，其余算正在注入。账号和昵称取它汇报的，取不到再看按 QQ 号另存的 `onebot_<QQ号>.json`。

“已连接”卡片在没登录时按这个进度说卡在哪一步（`snowLumaLoginHint`）。

### 登录：QQ 窗口（`QqScreen.kt`、`QqWindowDialog`）

SnowLuma 没有出二维码的接口，登录二维码只画在 QQ 自己的窗口里。所以 QQ 窗口弹窗开着时每秒读一次 Xvfb 的屏幕文件：`XwdScreen.decode` 按 XWD 文件头找到像素，没有窗口管理器，屏幕上除了 QQ 的窗口都是背景色，按和左上角那个像素不同框出 QQ 窗口那块画出来。用户点在画面上，按比例换算成屏幕坐标，用 `xdotool` 点进 QQ 窗口（刷新二维码、快速登录的“登录”按钮都靠它），连点时排队依次点。

点设置时：QQ 已登录就打开 SnowLuma 管理页（`/?token=<密码>`，页面自己登录，用户不用输密码）；没登录打开 QQ 窗口，登录成功后自动关掉。

### proot 版本要跟上 Termux

SnowLuma 的注入组件对 proot 版本敏感：MaiDroid 曾打包 Termux proot 5.1.107.82，SnowLuma（2.4.0～2.4.2）在它下面注入成功、认出账号，却始终收不到注入组件发来的登录状态，停在 `loaded`、`loggedIn: false`，不开 OneBot 服务。换成 Termux 当前的 5.1.107.102 后正常上线。注入后那条 `Client private-message reporting is unavailable status=-1` 在正常上线时也会出现，不是故障线索。升级 jniLibs 里的 proot 时，要把可执行文件依赖的 `libtalloc.so.2` 改名成 `libtalloc.so`（APK 里的原生库只能叫 `lib*.so`）。

认出账号却还没就绪时，“已连接”卡片写“QQ 已登录，等待 SnowLuma 接管”：SnowLuma 只有在 QQ 真正登录、打开账号数据之后才认得出账号，所以“认出了账号但没就绪”就不是用户还没登录（`SnowLumaPlatformState.accountDetected`）。

### 重启 QQ

SnowLuma 没有退出 QQ 登录的接口。配置弹窗里的“重启 QQ”（先二次确认，`SnowLumaRestartQqDialog`）只重启 SnowLuma 和 QQ，不动 MaiBot；换号也是重启后在 QQ 窗口里换。

## 新增一个平台的步骤

以新增“某平台”为例：

1. 在 `platform/` 下建一个包，按现有平台的分工放：
   - `XxxScripts`：安装脚本，返回 `ContainerScript`，克隆 GitHub 仓库用 `GitHubMirrors.shellFunctions()` + `maidroid_github_clone`；
   - `XxxFiles`：宿主侧的文件位置和“是否已装好”；
   - `XxxPlatformState`：界面状态；
   - `XxxPlatformManager`：安装、配置、状态汇总，构造函数注入 `RuntimeReadiness`、`ContainerCommandRunner` 等。
2. 需要长驻进程时写一个 `XxxProcessSupervisor`，并在 `MaiBotRuntimeService.onStartCommand` / `onDestroy` 里启停；再在 `SafeExitManager` 里加一项，让设置页的“安全退出”也停掉它（进程能自己保存数据的，用 `requestExitScript` 在容器里请它退出，见架构总览的“安全退出”）。
3. 在 `AppDependencies` 里加一个 `lazy` 属性。
4. 界面：在 `PlatformCatalog.AvailablePlatforms` 加平台（有多种接入方式时在 `AccessMethod` 加选项），在 `installedPlatformCards` 里按真实状态生成已安装卡片，弹窗放在 `ui/platform/` 的独立文件里。
5. 装好或改了配置后经 `ACTION_RESTART_RUNTIME` 重启 MaiBot。
6. 给状态推断、配置读写这类纯逻辑写 JVM 单元测试（参考 `NapCatQrCodeRefreshTest`、`QqOfficialPlatformTest`）。
