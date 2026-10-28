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

1. **解包资源**（`RuntimeAssetInstaller`，已解包就跳过）：rootfs 解到 `debian.tmp` -> bootstrap 和 deb 包合并解到 `termux.tmp` -> 写完成标记 -> 删旧目录、改名为正式目录 -> 修权限。中途被杀不会留下半成品；旧版本升级上来只缺 proot 依赖库时只补库。tar 里的硬链接、zip 里 `SYMLINKS.txt` 描述的符号链接都会还原，路径越界的条目直接报错。
2. **确认 proot 可执行**。
3. **系统依赖**（缺 git/curl 时才做，见 `ContainerSetupScripts`）：修复 rootfs 权限 -> 在 `/root/storage` 下建到 `/sdcard` 各目录的链接 -> 写 DNS（阿里、114、Google、Cloudflare）-> apt 换清华源并停用其他源 -> `apt-get install git ca-certificates curl`。每段脚本可重复执行，第一次会把原文件备份成 `*.maidroid.bak`。

## OOBE：安装 MaiBot（`OobeSetupManager.startInstall`）

1. 先重写一次 DNS 配置（与配置容器一步是同一段脚本），保证接下来克隆和下载时能解析域名。
2. **克隆源码**（`MaiBotScripts.clone`，已有就跳过）：经 GitHub 镜像浅克隆到 `/opt/MaiBot.tmp`，改名为 `/opt/MaiBot`，再读一次 HEAD 确认仓库可用。克隆时加 `-c core.createObject=rename`，原因见下文“proot 下的 git”。
3. **装 uv**（`curl … astral.sh/uv/install.sh | sh`，已有就跳过）。
4. **装依赖**（`uv sync`，已就绪就跳过）。
5. **协议**：EULA.md、PRIVACY.md 都在才算完成，见下文“协议 hash”。
6. **启动并等待 WebUI**：拉起前台服务，每秒探测一次 `http://127.0.0.1:8001/`，最多等 3 分钟。
7. 写 `setup_complete`，界面出现“进入 MaiDroid”。

两步的每条命令都会把完整命令行、输出和退出码写进安装页的终端日志和 `TerminalLog`。

### GitHub 镜像

国内直连 GitHub 不稳定。`GitHubMirrors.shellFunctions()` 定义 `maidroid_github_clone`、`maidroid_github_fetch` 两个 shell 函数，依次尝试 gitproxy.mrhjx.cn、ghproxy.vip、直连、gh-proxy.com 等镜像，每个镜像重试 3 次。新增镜像改 `MIRROR_SPECS` 即可，OOBE 卡片上的说明会自动更新。

### proot 下的 git

`--link2symlink` 会把 git 写 packfile 时用的硬链接换成指向绝对路径的符号链接。克隆到临时目录再改名后，这些链接还指着旧路径，git 读不到对象（`fatal: bad object HEAD`），在线更新和 MaiBot 自带的更新检查都会失败。所以容器里的 git 克隆一律加 `-c core.createObject=rename`，这个配置会写进仓库的 `.git/config`，之后的 fetch 也沿用。

### uv 的参数

`MaiBotScripts` 里所有 uv 命令共用一段前缀：`UV_PYTHON=">=3.12,<3.14"`（新版 Python 刚出时 aarch64 上很多包没有预编译轮子，容器里又没有编译器）、`UV_NO_DEV=1`（跳过测试依赖）、`UV_LINK_MODE=copy`。安装依赖和启动时 `uv run` 的自动同步用同一套参数，否则会反复重建 `.venv`。

容器里 `uv sync` 后 `uv.lock` 必然被改写（源地址换成镜像），`git status` 会一直显示它被修改，这不是用户改动。

## MaiBot 的启动

`MaiBotProcessSupervisor` 执行 `MaiBotScripts.launch()`：`cd /opt/MaiBot && uv run python bot.py`，容器里有 `script` 命令时套一层伪终端，让彩色日志按行刷新。启动前先跑 `cleanupStaleProcesses()` 清掉上次残留的 `bot.py` 和插件运行器进程。

### 协议 hash

MaiBot 首次启动要在终端里确认 EULA 和隐私协议，没有交互终端会一直卡住。它也接受环境变量预先确认：`EULA_AGREE`、`PRIVACY_AGREE` 分别等于两份协议文件的 MD5。用户已经在 MaiDroid 的授权弹窗里同意过，所以 `MaiBotAgreement.environment()` 算好 hash，只注入给启动 MaiBot 的那条命令。

## 在线更新（`maibot.update`）

OOBE 用浅克隆，`origin` 可能是早已失效的镜像地址，所以更新不用 `git pull`：

1. **检查**（`fetchLatest`，MaiBot 照常运行）：经镜像把上游当前分支拉到 `origin/<分支>`，比较提交。
2. **更新**（`fetchAndReset`）：`MaiBotProcessSupervisor.runWhileStopped` 停住 MaiBot -> 再拉一次 -> 有被改过的受版本管理文件就先 `git diff` 导出补丁到 `/root/.maidroid/maibot-backups/` -> `git reset --hard origin/<分支>` -> 必要时补装 uv -> `uv sync` -> 无论成败都重新拉起 MaiBot。

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
