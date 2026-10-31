# 容器运行时

MaiBot 是 Python 项目，要在 Debian 环境里用 uv 装依赖运行。MaiDroid 不需要 root：APK 自带 proot，把一个 Debian rootfs 挂成“假根目录”，所有命令都在这个容器里执行。这份文档讲容器的文件布局、命令怎么执行、OOBE 每一步做了什么，以及 MaiBot 的启动、更新和容器 SSH。

相关代码都在 `runtime/`，各功能模块的脚本在各自的包里（`maibot/MaiBotScripts`、`platform/napcat/NapCatScripts`、`ssh/ContainerSshScripts` 等）。

## 随 APK 分发的资源

| 位置 | 内容 | 用途 |
| --- | --- | --- |
| `assets/runtime/debian-rootfs.tar.xz` | Debian rootfs（arm64） | 解包成容器根目录 |
| `assets/runtime/bootstrap-aarch64.zip` | Termux bootstrap | proot 运行需要的基础工具和库 |
| `assets/runtime/proot_*.deb`、`libtalloc_*.deb`、`libandroid-shmem_*.deb` | Termux 的 proot 及依赖包 | 解包进 termux 目录 |
| `jniLibs/arm64-v8a/libproot_exec.so` | proot 可执行文件 | 实际执行的 proot，随 APK 装进 nativeLibraryDir |
| `jniLibs/arm64-v8a/libproot_loader.so`、`libtalloc.so`、`libandroid-shmem.so` | proot 的 loader 和依赖库 | 同上 |

proot 放在 jniLibs 里是因为 Android 10 以后应用私有目录不允许执行文件，只有 nativeLibraryDir 里的文件可以执行。`build.gradle.kts` 里的 `useLegacyPackaging = true` 让这些 .so 安装时解压出来，`noCompress` 让大体积的 xz/zip/deb 不被重复压缩。

只支持 arm64 设备。

## 文件布局（`RuntimePaths`）

```
filesDir/
├── debian/                     容器根目录（rootfs）
│   ├── .maidroid-rootfs-ready  解包完成标记
│   ├── opt/MaiBot/             MaiBot 源码（容器内 /opt/MaiBot）
│   │   ├── .venv/              uv 建的虚拟环境
│   │   ├── data/webui.json     WebUI 配置，含 access_token
│   │   └── plugins/            MaiBot 插件（NapCat 适配器、QQ 官方机器人适配器）
│   ├── proc/.stat 等           假 /proc 文件
│   └── root/                   容器内 /root：uv、napcat、launcher.sh、.maidroid/（MaiDroid 自己的状态文件）
├── termux/                     Termux bootstrap 和 proot 依赖
│   ├── .maidroid-bootstrap-ready
│   ├── lib/
│   └── tmp/                    绑定为容器的 /tmp
└── debian.tmp/ termux.tmp/     解包过程中的临时目录
nativeLibraryDir/               libproot_exec.so 等
```

容器内的固定路径常量在 `InContainerPaths`（例如 `/opt/MaiBot`），宿主侧路径在 `RuntimePaths`；NapCat 和 QQ 官方机器人的文件位置分别在 `NapCatFiles`、`QqOfficialAdapterFiles`。

## 在容器里执行命令

所有容器命令都写成 `ContainerScript`（脚本正文 + 容器内工作目录），交给 `ContainerCommandRunner`：

```kotlin
val result = commandRunner.run(MaiBotScripts.installUv()) { line -> TerminalLog.append(line) }
if (!result.success) fail(result.summary())
```

- `run(script)`：一次性命令，逐行回调输出，返回退出码和完整输出。进程起不来时不抛异常，返回退出码 -1。`summary()` 取最后一行非空输出作为失败原因。
- `start(script, extraEnvironment)`：长驻进程（MaiBot、NapCat、sshd），返回 `Process`，读输出和停止由调用方负责。
- `commandLine(script)`：实际执行的 proot 命令行，OOBE 会把它打进日志。

`ProotCommand` 负责翻译：

```
libproot_exec.so --link2symlink -0 -r <rootfs>
  -b /dev -b /proc -b /sys -b /sdcard
  -b <假 /proc 文件>:/proc/stat（以及 loadavg、vmstat、uptime）
  -b <termux/tmp>:/tmp
  -w <工作目录>
  /bin/sh -c "unset LD_LIBRARY_PATH; export PATH=… HOME=/root …; <脚本正文>"
```

- `-0` 假装 root，`--link2symlink` 让不支持硬链接的应用私有目录也能装 Debian 包。
- proot 进程本身的环境变量里有 `PROOT_LOADER`、`LD_LIBRARY_PATH`（指向 nativeLibraryDir 和 termux/lib）；进入容器后脚本前缀会 unset `LD_LIBRARY_PATH`，避免 Android 的库污染 Debian 程序。
- Android 不让普通应用读 `/proc/stat` 等文件，psutil、uv 读不到会报错，所以写一份全零的假文件绑进去。

写脚本时用 `shellQuote()` 包外部输入；需要结束残留进程用 `killMatchingProcessesScript()`。

## 就绪检查（`RuntimeReadiness`）

只看文件，不跑命令：

| 部件（`RuntimeComponent`） | 判断依据 |
| --- | --- |
| Debian rootfs | `debian/` 存在且有解包完成标记 |
| Termux bootstrap | 解包完成标记 |
| proot | nativeLibraryDir 里四个 .so 都可执行 |
| Git | 容器里 `/usr/bin/git` 可执行 |
| curl / CA | `/usr/bin/curl` 可执行且有 CA 证书包 |
| MaiBot 源码 | `bot.py` 和 `pyproject.toml` 存在 |
| Python 依赖 | `.venv` 有 `pyvenv.cfg`、python 可执行，且 site-packages 里真有包 |

最后一条要看 site-packages：`uv sync` 一开始就建好 `.venv` 的壳，装到一半失败时壳还在，只看壳会把失败当成功。

后台任务开工前统一调 `containerProblem()`：先补 proot 可执行位（覆盖安装 APK 后可能丢），再返回第一个没就绪的部件说明。

## OOBE：配置容器（`OobeSetupManager.prepareContainer`）

1. **解包资源**（`RuntimeAssetInstaller`，已解包就跳过）：rootfs 解到 `debian.tmp` -> bootstrap 和 deb 包合并解到 `termux.tmp` -> 写完成标记 -> 删旧目录、改名为正式目录 -> 修权限。中途被杀不会留下半成品；旧版本升级上来只缺 proot 依赖库时只补库。tar 里的硬链接、zip 里 `SYMLINKS.txt` 描述的符号链接都会还原（硬链接建不了就复制），还原不了直接报错，路径越界的条目也直接报错。删旧目录时不跟随符号链接：rootfs 里的 `/root/storage/shared -> /sdcard` 在宿主上指向手机共享存储，跟进去会删掉用户文件。
2. **确认 proot 可执行**。
3. **系统依赖**（缺 git/curl 时才做，见 `ContainerSetupScripts`）：修复 rootfs 权限 -> 在 `/root/storage` 下建到 `/sdcard` 各目录的链接 -> 写 DNS（见下文“容器 DNS”）-> apt 换清华源并停用其他源 -> 安装 git、ca-certificates、curl。每段脚本可重复执行，第一次会把原文件备份成 `*.maidroid.bak`。安装前先 `dpkg --configure -a`：OOBE 期间没有前台服务，装到一半切到后台或划掉应用都会让进程被杀，留下 dpkg 半装状态，不收拾的话之后每次都报 `dpkg was interrupted`。`apt-get update` 有源下载失败时默认只警告、返回 0，这里加 `--error-on=any` 让它如实失败。

## OOBE：安装 MaiBot（`OobeSetupManager.startInstall`）

进入这一步前 `MainViewModel` 先用 `containerProblem()` 确认容器就绪，没就绪就退回“配置容器”一步。

1. 先重写一次 DNS 配置（与配置容器一步是同一段脚本），保证接下来克隆和下载时能解析域名。
2. **克隆源码**（`MaiBotScripts.clone`，已有就跳过）：经 GitHub 镜像浅克隆到 `/opt/MaiBot.tmp`，改名为 `/opt/MaiBot`，再读一次 HEAD 确认仓库可用。克隆时加 `-c core.createObject=rename`，原因见下文“proot 下的 git”。
3. **依赖**：装 uv、准备 Python 解释器、`uv sync` 三步每次都跑，见下文“依赖安装”。已就绪时 uv、Python 立刻跳过，`uv sync` 只核对一遍（真机上几十毫秒）；以前按“site-packages 非空”整段跳过，装包时被打断留下的半套依赖会被当成装好。`uv sync` 返回成功后再按文件核对一次 `.venv` 里确实有包。
4. **协议**：hash 在启动 MaiBot 时现算现注入，这一步只在协议文件缺失时往终端里提示一句，不拦着安装，见下文“协议 hash”。
5. **启动并等待 WebUI**：先请求前台服务，再直接调 `MaiBotProcessSupervisor.start()`（应用在后台时系统会拒绝新开前台服务，这样 MaiBot 照样能起来）。之后每秒按 `maiBotStartupVerdict` 判断一次，见下文“等 WebUI 的判断”。
6. 写 `setup_complete`，界面出现“进入 MaiDroid”。

两步的每条命令都会把完整命令行、输出和退出码写进安装页的终端日志和 `TerminalLog`。

两步结束时要么完成、要么有一张失败卡片且页脚按钮可以重试：脚本返回成功后还会按文件核对一次（配置容器最后核对容器五件套），意外异常也记成当前那张卡片失败。

安装页的四张卡片按顺序判断完成：前一步没完成，后面的卡片即使文件已经在（例如协议文件随源码一起下载）也显示为等待。页脚左侧的标志跟着按钮走：可以继续时打勾，只能重试时显示失败图标，其余时候转圈。

### GitHub 镜像

国内直连 GitHub 不稳定。`GitHubMirrors.shellFunctions()` 定义 `maidroid_github_clone`、`maidroid_github_fetch` 两个 shell 函数，依次尝试 gitproxy.mrhjx.cn、ghproxy.vip、直连、gh-proxy.com 等镜像，每个镜像重试 3 次。新增镜像改 `MIRROR_SPECS` 即可，OOBE 卡片上的说明会自动更新。

git 默认不检测传输停滞，镜像连上后卡住会一直等。两个函数都带 `-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60`：连续 60 秒低于 1 KB/s 就判这一次失败，接着重试或换镜像。

### 容器 DNS

proot 里没有 Android 的 DNS 解析服务，容器程序直接向 `/etc/resolv.conf` 里的服务器查询。`ContainerDns` 在宿主侧读出手机当前网络的 DNS（`ConnectivityManager` 的 `LinkProperties.dnsServers`）放在最前，阿里、114、Google、Cloudflare 作兜底；有些网络会拦截公共 DNS，只写公共 DNS 时容器里所有下载都会失败。glibc 只用前三个 nameserver、按顺序尝试，所以不加 `rotate`；带网卡后缀的 IPv6 链路本地地址会被丢掉。配置容器和每次开始安装时都会重写一次。

### 容器时区

Debian rootfs 出厂时区是 `Etc/UTC`，麦麦按容器时间报时，国内手机上会差 8 个小时。`ContainerTimeZone` 在宿主侧把 rootfs 的 `/etc/localtime` 指向手机当前时区的 zoneinfo 文件，`/etc/timezone` 写同一个名字：

- `ProotCommand` 在每条容器命令启动前同步一次，内容没变不写盘；手机在应用没跑时改过时区，下次拉起的进程也会用上新时区。同步失败不挡命令，容器沿用原时区。
- 前台服务活着时监听 `ACTION_TIMEZONE_CHANGED`：收到后立刻同步，MaiBot 正在跑就重启它。进程启动时就读定了时区，不重启会一直按旧时区报时。NapCat 不重启（会断开 QQ 登录），它只影响自己的日志时间，下次启动用上新时区；SSH 里新开的命令直接读 `/etc/localtime`，同步后立刻生效。
- 容器的 tzdata 里没有手机的时区名（手机的 tzdata 更新，或是 `GMT+08:00` 这类自定义时区）时，按当下的偏移换成 `Etc/GMT∓N`（正负号和 UTC+8 的写法相反），偏移不是整点就退回 `Etc/UTC`。

### proot 下的 git

`--link2symlink` 会把 git 写 packfile 时用的硬链接换成指向绝对路径的符号链接。克隆到临时目录再改名后，这些链接还指着旧路径，git 读不到对象（`fatal: bad object HEAD`），在线更新和 MaiBot 自带的更新检查都会失败。所以容器里的 git 克隆一律加 `-c core.createObject=rename`，这个配置会写进仓库的 `.git/config`，之后的 fetch 也沿用。

### 依赖安装

装 uv、准备 Python 解释器、装依赖包三步由 `MaiBotDependencyStep` 定义，OOBE 和在线更新共用。三段脚本都能重复执行，已经就绪的部分会自己跳过：

1. **装 uv**：先把官方安装脚本下载成文件（`astral.sh/uv/install.sh`，拿不到就经 GitHub 镜像取发布页上的同一份 `uv-installer.sh`），确认是 shell 脚本（以 `#!` 开头、提到 `astral-sh/uv`，不依赖安装脚本内部写法）再执行。安装先走官方下载源（releases.astral.sh、github.com），失败后把 `UV_INSTALLER_GITHUB_BASE_URL` 依次换成各 GitHub 镜像重试；安装脚本里带着安装包的 sha256，经镜像下载的包也会校验。每次装完都跑 `uv --version`，能运行才算成功。
2. **准备 Python**：在 `/tmp` 下 `uv python find` 找到符合 `UV_PYTHON` 的解释器就跳过（避开 MaiBot 目录里装到一半的 `.venv`）；否则 `uv python install`，官方源失败后用 `UV_PYTHON_INSTALL_MIRROR` 依次换成各 GitHub 镜像（含直连）。
3. **装依赖包**：`uv sync`。

几点约定：

- 不能写成 `curl … | sh`：管道的退出码只看 `sh`，curl 下载失败时 `sh` 读到空脚本照样返回 0，安装被当成成功，到 `uv sync` 才报 `uv: not found`。
- uv 安装脚本自己调 curl 时不设超时，镜像卡住会一直等。装 uv 的脚本用临时 `.curlrc`（`CURL_HOME`）给其中所有 curl 加上：20 秒连不上、或连续 30 秒低于 1 KB/s 就换下一个来源。uv 自己下载 Python 和包时有内置的超时和重试。
- 后两步开头先确认 uv 能运行，不能运行就打印中文原因、以 127 退出，不会只留下一句 `uv: not found`。
- 失败文案：前两步的脚本退出前会自己打印中文原因（最后一行）；`uv sync` 的原因由 `uvErrorSummary` 从 uv 输出里挑第一条 `error:`/`×` 行和最后一条 `cause:`/`╰─▶` 行拼成。uv 报错后面常跟着 `hint:`，只取最后一行会丢掉真正的错误。

### uv 的参数

`MaiBotScripts` 里所有 uv 命令共用一段前缀：`UV_PYTHON` 限定 Python 的版本范围，具体范围见 `MaiBotScripts.UV_PYTHON_REQUEST`（不放开上限，因为新版 Python 刚出时 aarch64 上很多包没有预编译轮子，容器里又没有编译器）、`UV_NO_DEV=1`（跳过测试依赖）、`UV_LINK_MODE=copy`。安装依赖和启动时 `uv run` 的自动同步用同一套参数，否则会反复重建 `.venv`。

容器里 `uv sync` 后 `uv.lock` 必然被改写（源地址换成镜像），`git status` 会一直显示它被修改，这不是用户改动。

## MaiBot 的启动

`MaiBotProcessSupervisor` 执行 `MaiBotScripts.launch()`：`cd /opt/MaiBot && uv run python bot.py`，容器里有 `script` 命令时套一层伪终端，让彩色日志按行刷新。启动前先跑 `cleanupStaleProcesses()` 清掉上次残留的 `bot.py` 和插件运行器进程。

### 等 WebUI 的判断

`MaiBotProcessSupervisor.status` 记着进程是否在跑、意外退出的累计次数和最后退出码（主动停止、重启不算），以及启动前检查没过时被拦下的原因。OOBE 拉起 MaiBot 前记下当时的退出次数，之后每秒把状态和 WebUI 探测结果交给 `maiBotStartupVerdict`（`MaiBotStartup.kt`）：

| 情况 | 结论 |
| --- | --- |
| WebUI 有响应且进程在跑 | 完成（只看端口会把占着 8001 的别的程序当成 MaiBot） |
| 启动前检查没过 | 立刻失败，报被拦下的原因 |
| 拉起后意外退出满 2 次 | 立刻失败，报退出次数和最后退出码；允许一次是因为 MaiBot 可能自己退出请求重启 |
| 60 秒内进程没起来也没退出过 | 失败：进程一直没有启动 |
| 进程活着满 10 分钟 WebUI 仍不通 | 失败：超时 |

以前只等 3 分钟有没有 HTTP 响应：MaiBot 起来就崩也要空等满 3 分钟、只报一句超时，低端机首次启动超过 3 分钟又会被误判。

### 协议 hash

MaiBot 首次启动要在终端里确认 EULA 和隐私协议，没有交互终端会一直卡住。它也接受环境变量预先确认：`EULA_AGREE`、`PRIVACY_AGREE` 分别等于两份协议文件的 MD5。用户已经在 MaiDroid 的授权弹窗里同意过，所以 `MaiBotAgreement.environment()` 算好 hash，只注入给启动 MaiBot 的那条命令。

hash 要按 MaiBot 的算法算（`agreementHash`）：它用 Python 文本模式读文件，`\r\n` 和单独的 `\r` 都变成 `\n`，再按 UTF-8 算 MD5。直接对原始字节算的话，上游提交了 CRLF 换行的协议文件时 hash 对不上，MaiBot 会停在终端里等人输入“同意”。文件缺失的那份不注入，也不拦着安装：上游不再要求确认时照常启动，仍要求时 MaiBot 自己报错退出，由上面的“反复退出”报出来。

## 在线更新（`maibot.update`）

OOBE 用浅克隆，`origin` 可能是早已失效的镜像地址，所以更新不用 `git pull`：

1. **检查**（`fetchLatest`，MaiBot 照常运行）：经镜像把上游当前分支拉到 `origin/<分支>`，比较提交。
2. **更新**（`fetchAndReset`）：`MaiBotProcessSupervisor.runWhileStopped` 停住 MaiBot -> 再拉一次 -> 有被改过的受版本管理文件就先 `git diff` 导出补丁到 `/root/.maidroid/maibot-backups/` -> `git reset --hard origin/<分支>` -> 按上文“依赖安装”的三步重装依赖（uv、Python 已就绪时立刻跳过，上游改了 Python 版本要求时也能补装）-> 无论成败都重新拉起 MaiBot。

被 git 忽略的配置、数据、插件和 `.venv` 不受影响。脚本把版本信息按 `MAIDROID_UPDATE_*=…` 打印出来，`MaiBotUpdateReport` 从输出里挑这些行解析。

## 容器 SSH（`ssh/`）

设置页可以在容器里开 sshd，方便用电脑连进去排查：

1. **配置**（`ContainerSshScripts.configure`）：修复 dpkg 半装状态 -> 没有 sshd 就 apt 装 openssh-server（失败会尝试修 dpkg 再装一次）-> 补齐 sshd 用户和目录 -> 改写 sshd_config 的端口、允许 root 密码登录 -> 设置 root 密码 -> 生成主机密钥并自检配置。
2. **清理**残留 sshd -> **前台运行** `sshd -D -e -p <端口>`。

端口和密码存在 `AppSettings`，sshd 的生命周期由前台服务托管。

## 常见问题定位

- 容器命令的完整输出都在 `TerminalLog`，`adb logcat -s TerminalLog` 可以直接拿到。
- 在电脑上手动复现容器命令：`adb shell run-as org.maiwithu.maidroid`，用 `lib/arm64/libproot_exec.so` 加上和 `ProotCommand` 相同的参数、`PROOT_LOADER` 环境变量即可进入容器。容器里的 `/bin/sh` 是 dash，没有 `time` 等 bash 内建。
- 解包失败先看剩余存储空间是否足够，再确认装的是 arm64 设备上的 APK；失败原因会显示在 OOBE 页脚和终端日志里。
