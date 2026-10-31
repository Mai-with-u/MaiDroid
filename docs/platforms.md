# 消息平台接入

MaiBot 通过插件（适配器）连接聊天平台。MaiDroid 的“消息平台”页负责把这些适配器装进容器里的 MaiBot、填好配置、展示连接状态。目前接入了 QQ 的两种方式：

| 接入方式 | 原理 | 代码 |
| --- | --- | --- |
| QQ 官方机器人 | MaiBot-QQ-Adapter 插件直连 QQ 开放平台，需要在 q.qq.com 申请 AppID、AppSecret | `platform/qqofficial/`、`ui/platform/QqOfficialConfigDialog.kt` |
| NapCat | 容器里跑 LinuxQQ + NapCat，扫码登录个人 QQ，NapCat 提供 OneBot11 WebSocket，MaiBot-SnowLuma-Adapter 插件去连 | `platform/napcat/`、`ui/platform/NapCatDialogs.kt` |

两者都是 MaiBot 的插件，装好或改了配置后都要重启 MaiBot 才生效，统一经 `MaiBotRuntimeService.start(context, ACTION_RESTART_RUNTIME)`。

## 消息平台页的结构

- `PlatformCatalog.kt`：“发现”区展示哪些平台（`AvailablePlatforms`），每个平台可选的接入方式（`PlatformAdapter`），以及“已安装”行按真实状态生成卡片的规则（`installedPlatformCards`）。设置页的“运行中平台数”也按这里统计。
- `MessagePlatformsPage.kt`：页面本身，持有各弹窗的开关（`rememberSaveable`，用户切去 QQ 扫码导致 Activity 重建后弹窗还在）。
- `PlatformCards.kt`：已安装小卡片、平台安装卡片（右上角按钮按选中的接入方式显示下载 / 进度环 / 设置）。
- `AdapterSelector.kt`：接入方式选择器。
- `NapCatDialogs.kt`、`QqOfficialConfigDialog.kt`：两种接入方式各自的弹窗。

Discord、WhatsApp、Signal 和 QQ 的 SnowLuma 选项目前只是展示，点下载会提示“还没有做适配”。注意区分：“SnowLuma”选项指在手机上跑 SnowLuma 客户端（代替 NapCat），没有做；NapCat 选项装的 MaiBot 侧插件是 MaiBot-SnowLuma-Adapter，它同时支持 NapCat 和 SnowLuma 两种客户端。

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

### 安装（`NapCatPlatformManager.installNapCat`、`NapCatScripts.install`、`NapCatAdapterInstaller`）

1. 检查容器、MaiBot 源码和 MaiBot 版本：QQ 适配器要求 MaiBot 1.3.18 以上（`NapCatAdapterScripts.MIN_MAIBOT_VERSION`），MaiBot 会直接拒绝加载版本范围外的插件，所以版本不够时提示先去设置页更新 MaiBot。版本号读自 `pyproject.toml` 的 `[project].version`。
2. NapCat 本体还没装（`napcat_ready` 标记或 `onebot11.json` 缺一个）才运行安装脚本，进度通过容器 `$TMPDIR/progress`、`progress_des` 两个文件汇报，每 500 毫秒读一次显示在卡片的进度环上：
   - 生成 OneBot 访问 token（只在第一次安装时生成，存在 `/root/.maidroid/napcat_token`，重装沿用）；
   - 下载并运行 NapCat 官方的 Linux 安装器；
   - 写 `/root/napcat/config/onebot11.json`：在 `127.0.0.1:8095` 开 WebSocket 服务端，带上 token；
   - 写 `/root/.maidroid/napcat_ready` 标记，生成 `/root/launcher.sh`。
3. 装 MaiBot 侧的 QQ 适配器（`NapCatAdapterScripts.install`）：没装过（按清单 ID `maibot-team.snowluma-adapter` 找，插件市场装的副本也算）就经 GitHub 镜像把 MaiBot-SnowLuma-Adapter 克隆到 `plugins/` 下以点开头的临时目录，再改名为 `plugins/MaiBot-SnowLuma-Adapter`；然后删掉清单 ID 为 `maibot-team.napcat-adapter` 的旧适配器（两个适配器同时连 NapCat 会把每条消息收两遍）。删之前先把旧适配器的 `config.toml` 读进内存，留给第 5 步搬设置。
4. 经前台服务拉起 NapCat 并重启 MaiBot。整个目录移进 `plugins/` 时 MaiBot 不一定能热加载，所以要重启；重启时 MaiBot 发现插件没有 `config.toml`，会按适配器自己的默认值生成一份（带 MaiBot 强制要求的 `plugin.config_version`，默认 `enabled = false`，适配器先空闲着）。新适配器依赖 `silk-python`（语音转码），MaiBot 加载插件时自己用 uv 补装，有 aarch64 预编译包。
5. 等配置生成后迁移（`NapCatAdapterInstaller.migrateConfigWhenReady` -> `NapCatAdapterConfig.migrate`）：每 2 秒看一次，最多等 5 分钟；看到带 `config_version` 的配置后再等 5 秒（MaiBot 写完配置才订阅它的变化，改早了会错过热重载），然后在生成的配置上改：`[plugin].enabled = true`，`[client]` 的 `server`、`port`、`token` 写成 `127.0.0.1`、8095 和上面的 token，再把旧适配器里有对应项的设置搬过来（见下）。其余内容原样保留。MaiBot 监听插件配置文件，发现启用了会补装依赖并热启用适配器，不用再重启。

MaiDroid 不自己从头写这份配置：MaiBot 1.3.19 要求插件配置必须带 `plugin.config_version`，版本号和各段默认值都以适配器当前版本为准，由 MaiBot 生成最稳。“迁移过没有”只看 `[client]` 是否指向本机 NapCat（`NapCatAdapterConfig.isMigrated`），不看 `enabled`：用户之后在 WebUI 里关掉适配器，MaiDroid 不会每次启动都给打开。

注意：NapCat 安装器本身仍直连 GitHub 下载，没有走镜像。

### 旧适配器迁移

0.1.3 及更早的 MaiDroid 装的是 MaiBot-Napcat-Adapter，它已经停更（清单名写着“不再更新，请使用Snowluma适配器”）。`NapCatProcessSupervisor` 每次启动 NapCat 前检查一次：还没有新适配器或旧的还在，就按上面第 3、4 步换装并重启 MaiBot，再在后台等配置生成后迁移（第 5 步），不耽误 QQ 启动。只差配置迁移的（上次等配置时应用被杀或超时）直接在后台等着迁移。

- MaiBot 低于 1.3.18 时先不动，旧适配器继续用，终端日志提示去更新 MaiBot；更新完下次启动 NapCat 时再换。
- 下载失败时旧适配器还在（先装新的、再删旧的），下次启动 NapCat 再试。
- 旧配置里有对应项的设置原样搬到新配置：`[plugin].enable_private_chat_tool` -> `[chat_abilities]`，`[chat].ban_user_id`、`ban_qq_bot` -> `[filters]`，`[filters].ignore_self_message` -> `[debug]`，`[filters]` 的四个 `regex_filter_*` 和 `[notice].enabled` 原位搬。只搬写在一行内的值（`PluginConfigToml.isCompleteValue`），多行数组搬过来会写坏文件。旧的群聊、私聊名单新适配器没有对应功能，不搬（新适配器默认全部接收）；连接项以 MaiDroid 写的为准。
- 旧配置只在内存里留到迁移完成，应用中途被杀就只迁连接项。

### 运行（`NapCatProcessSupervisor`）

每次启动：需要时换装 QQ 适配器（见上）-> 清掉残留的 Xvfb、qq、launcher 进程和 X11 锁 -> 重新生成 `launcher.sh` -> 前台运行它（起一个 1x1 的 Xvfb，再用 `LD_PRELOAD` 注入 NapCat 启动 LinuxQQ）。输出加 `[NapCat]` 前缀写进终端日志。NapCat 退出后不自动重启。只要 NapCat 本体装好就启动，不看适配器装没装。

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

## 新增一个平台的步骤

以新增“某平台”为例：

1. 在 `platform/` 下建一个包，按现有两个平台的分工放：
   - `XxxScripts`：安装脚本，返回 `ContainerScript`，克隆 GitHub 仓库用 `GitHubMirrors.shellFunctions()` + `maidroid_github_clone`；
   - `XxxFiles`：宿主侧的文件位置和“是否已装好”；
   - `XxxPlatformState`：界面状态；
   - `XxxPlatformManager`：安装、配置、状态汇总，构造函数注入 `RuntimeReadiness`、`ContainerCommandRunner` 等。
2. 需要长驻进程时写一个 `XxxProcessSupervisor`，并在 `MaiBotRuntimeService.onStartCommand` / `onDestroy` 里启停。
3. 在 `AppDependencies` 里加一个 `lazy` 属性。
4. 界面：在 `PlatformCatalog.AvailablePlatforms` 加平台（有多种接入方式时在 `PlatformAdapter` 加选项），在 `installedPlatformCards` 里按真实状态生成已安装卡片，弹窗放在 `ui/platform/` 的独立文件里。
5. 装好或改了配置后经 `ACTION_RESTART_RUNTIME` 重启 MaiBot。
6. 给状态推断、配置读写这类纯逻辑写 JVM 单元测试（参考 `NapCatQrCodeRefreshTest`、`QqOfficialPlatformTest`）。
