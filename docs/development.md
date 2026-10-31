# 开发指南

本地构建、测试、真机调试，以及往这个仓库里加代码时要遵守的约定。架构和各包职责先看 [architecture.md](architecture.md)。

## 环境

| 项 | 要求 |
| --- | --- |
| JDK | 运行 Gradle 用 JDK 17 以上即可；Gradle 守护进程按 `gradle/gradle-daemon-jvm.properties` 使用 JDK 21，本机没有时会经 foojay 自动下载 |
| Android SDK | compileSdk 36（36.1），缺平台时 AGP 会自动安装（需要已接受许可证） |
| AGP / Kotlin | 9.2.1 / 2.2.10，版本统一在 `gradle/libs.versions.toml` |
| 设备 | arm64，Android 8.0（API 26）以上；APK 只带 arm64 的 proot |

`local.properties` 里写 `sdk.dir`，不进仓库。需要代理时把 `systemProp.http(s).proxyHost/Port` 写进自己的 `~/.gradle/gradle.properties`，不要写进仓库的 `gradle.properties`（CI 和别人的机器没有这个代理）。

## 构建与测试

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Windows PowerShell 用 `.\gradlew.bat`。`./gradlew check` 还会跑 `checkUtf8Encoding`，检查所有文本文件都是 UTF-8。

在 Git Bash 里 `./gradlew` 可能把 `JAVA_HOME` 转成 `/cygdrive/...` 后找不到 java。遇到时直接用 JDK 跑 wrapper：

```bash
"$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。debug 包用每台机器自动生成的调试密钥签名，不同机器构建的包互相覆盖安装前要先卸载（卸载会清掉容器数据）。发布流程见 README 的“发布与签名”。

### 单元测试覆盖了什么

只有 JVM 单元测试（`app/src/test`），没有仪器测试。可测的纯逻辑都写成 `internal` 顶层函数或无 Android 依赖的类，方便直接测：

| 测试 | 覆盖 |
| --- | --- |
| `files/ContainerDocumentsTest` | 容器文件共享：documentId 与路径互转、拒绝越界 id、按容器语义解析符号链接（l2s 替身、绝对路径链接、链接成环）、MIME 推断、新建时避开重名 |
| `maibot/MaiBotDependencyStepTest` | 依赖安装失败文案、从 uv 输出里提炼失败原因 |
| `maibot/MaiBotStartupTest` | 等 WebUI 时的判断：就绪、反复退出、没拉起、超时 |
| `maibot/MaiBotAgreementTest` | 协议 hash 与 MaiBot 的算法一致（换行归一） |
| `maibot/PluginDirLookupTest` | 按清单 ID 找插件目录（插件市场副本、临时目录、保留目录） |
| `maibot/MaiBotProjectVersionTest` | 从 pyproject.toml 读 MaiBot 版本 |
| `maibot/PluginConfigTomlTest` | 判断配置值是否在一行内写完 |
| `maibot/update/MaiBotUpdateReportTest` | 在线更新脚本输出的解析 |
| `oobe/OobeSetupStateTest` | 安装卡片按顺序判断完成 |
| `platform/napcat/NapCatAdapterConfigTest` | QQ 适配器配置迁移：识别 MaiBot 生成的配置、启用并连本机 NapCat、搬旧适配器的对应设置、跳过多行值；MaiBot 版本门槛；安装脚本克隆新适配器并按清单 ID 删旧适配器 |
| `platform/napcat/NapCatQrCodeRefreshTest` | 二维码自动换码策略 |
| `platform/napcat/NapCatWebUiPageTest` | NapCat 管理页地址、扫码完成判断 |
| `platform/qqofficial/QqOfficialPlatformTest` | config.toml 读写、连接报错识别、状态文案 |
| `runtime/ContainerDnsTest` | 容器 resolv.conf 生成：系统 DNS 优先、过滤不可用地址 |
| `runtime/ContainerTimeZoneTest` | 容器时区跟随手机：容器有同名 zoneinfo 就用原名，没有就按当前偏移换成 Etc/GMT∓N，半点偏移退回 UTC，挡掉越出 zoneinfo 的时区名；改写 /etc/localtime 和 /etc/timezone |
| `runtime/GitHubMirrorsTest` | 宿主侧可用的下载线路、容器脚本里的镜像列表 |
| `appupdate/AppReleaseTest` | 应用自更新：版本号比较、Release 解析与挑选 APK、校验行、更新说明排版、断点续传响应判断 |
| `appupdate/AppUpdateClientTest` | 应用自更新下载：断点续传、断线后接着下、服务器不支持 Range 时从头下、测速 |
| `appupdate/AppUpdateFilesTest`、`appupdate/AppPackageCheckTest` | 安装包命名与清理、能否覆盖安装、下载后核对包名版本号签名 |
| `ui/oobe/OobeFlowScreenTest` | OOBE 布局模式与终端展开规则 |
| `ui/platform/MessagePlatformsPageTest` | 按窗口选排版（手机列表、横屏两栏、平板网格）、平台网格列数、选择器宽度、平台目录 |
| `ui/settings/*Test` | 设置页分栏规则、MaiBot 与应用更新弹窗的按钮与文案 |
| `ui/splash/StartupSplashPageTest` | 启动页几何计算 |
| `webui/MaiBotWebUiTest` | 访问令牌提取、HTML 注入、主题只补默认值、样式表安全区改写、视口修补脚本 |
| `webui/WebUiDesktopViewportTest` | 横屏桌面版的布局宽度（不小于 1040 宽，高度不设下限）、改写 viewport meta |
| `webui/WebUiFileChooserTest` | 网页 accept 属性转成选文件页的 MIME 类型 |

改了这些逻辑要同步更新测试；新写的纯逻辑也照这个方式补测试。界面和容器命令靠真机验证。

## 真机调试

### 看日志

| logcat 标签 | 内容 |
| --- | --- |
| `TerminalLog` | 终端输出的每一行：MaiBot、NapCat、sshd、OOBE 命令、在线更新（D 级别） |
| `MaiDroidWebUi` | WebView 加载、网页 console 输出、视口修补结果 |
| `MaiBotProcessSupervisor`、`MaiBotRuntimeService`、`BootCompletedReceiver` | 进程监管、前台服务、开机广播 |

```bash
adb logcat -s TerminalLog
```

### 调试 WebUI 页面

debug 包开了 WebView 远程调试：电脑 Chrome 打开 `chrome://inspect` 就能看到页面，可以查 DOM、改 CSS、在活页面里试脚本。也可以 `adb forward tcp:9222 localabstract:webview_devtools_remote_<应用 pid>` 后用 CDP 直接连。

### 进入容器

```bash
adb shell run-as org.maiwithu.maidroid
```

在应用目录里用 `lib/arm64/libproot_exec.so`、设置 `PROOT_LOADER`，按 `ProotCommand` 的参数就能进入容器手动复现命令。

### 调试应用自更新

debug 包的签名和发布版不同，查到新版本也只会给“打开下载页”。要在真机上把“下载 -> 校验 -> 系统安装”走一遍，让它查一个本机的假 Release 源：

1. 用默认配置构建一个 debug 包（版本 B），算出它的 SHA-256；在电脑上起一个支持 Range 的静态服务，放这个 APK 和一份仿 `releases/latest` 的 JSON：`tag_name` 写 B 的版本号、`assets` 指向这个 APK，`body` 末尾照发布流程的格式写校验行，证书 SHA-256 填本机调试证书的（`apksigner verify --print-certs` 可以看）。
2. `adb reverse tcp:18080 tcp:18080` 后，把 `versionName`、`versionCode` 临时改得比 B 低，加 `-PappUpdateApiUrl=http://127.0.0.1:18080/latest` 构建版本 A 装到手机上（比手机上已装的旧时用 `adb install -r -d`）。别用 8000：手机上 MaiBot 自己占着 8000，`adb reverse` 会绑不上。
3. 在设置页点“应用版本”，走完下载、取消再继续、安装。非 GitHub 地址只走直连，不经镜像。荣耀、华为手机的系统安装器查验完还会转到应用市场的风险检查页（“应用未备案”），要在手机上手动确认才能继续安装。

`-PappUpdateApiUrl` 只影响本地构建，CI 和日常构建都用默认的 GitHub 地址。

### 调试 NapCat

NapCat 的 WebUI 端口和 token 在容器 `/root/napcat/config/webui.json`（默认 6099）。转发到电脑后在浏览器打开 `/webui/?token=<token>`：

```bash
adb forward tcp:16099 tcp:6099
```

想模拟“二维码已过期”，可以用 `run-as` 把 `cache/qrcode.png` 的修改时间改旧。

### 已知的坑

- `adb install -r` 会强停应用，NapCat 随之重启，QQ 需要重新扫码。
- `WebUiActivity` 没有导出，`adb shell am start` 启动不了，只能从主页或消息平台页进入。
- 用户切到 QQ 扫码再回来时，系统可能因配置变化重建 `MainActivity`。弹窗开关要用 `rememberSaveable`，判断“弹窗是不是被代码关掉的”之前先排除这一点。
- 部分厂商的安全键盘在密码框聚焦时禁止截屏，用 `adb shell input` 驱动界面时先收起键盘再截图。
- 容器里 `uv sync` 后 `uv.lock` 必然被改写，MaiBot 仓库的 `git status` 一直显示它被修改，在线更新会每次备份一份补丁，这不是用户改动。

## 代码约定

### 东西放哪

- 包按功能划分，职责见 architecture.md 的包结构表。不要再建 `screen`、`utils`、`common` 这类按技术分的包。
- 长生命周期的组件在 `AppDependencies` 里创建、用构造函数注入依赖，不写 `get(context)` 式的单例，也不要在 `object` 里放可变状态。
- 任何要在容器里跑的东西都写成 `ContainerScript`，放进所属模块的 `*Scripts` 对象，交给 `ContainerCommandRunner` 执行；不要自己拼 proot 命令行。
- 宿主侧文件位置放 `RuntimePaths`（运行时通用）或模块自己的 `*Files`；就绪判断放 `RuntimeReadiness`。

### 命名

- 类名、文件名说清“它是什么”：`*Manager` 管业务流程和界面状态，`*Supervisor` 管长驻进程，`*Scripts` 产出容器脚本，`*Files` 描述文件位置，`*State` 是界面状态。
- 用枚举表达有限的取值（`OobeStep`、`OobeTaskKind`、`RuntimeComponent`、`PlatformAdapter`、`NapCatLoginPhase`），不要用魔数和用于比较的显示文字。
- 不写全限定名，用 import。

### 界面

- 颜色只从 `ui/theme/Color.kt` 取，按用途命名；只有某一页专用的配色才在那一页私有定义。文字和图标的主色用 `TextPrimary`，次要文字用 `TextMuted`、`TextFaint`。
- 字号从 `TextSizes`、圆角从 `Corners` 取（都在 `ui/theme/Dimens.kt`，按角色分档），不在页面里写死 sp、dp。唤醒页、启动页的插画按设计稿等比缩放，是例外。
- 可点击的东西用默认的按压水波纹；只有遮罩、吞点击的全屏背景才写 `indication = null`，并在旁边注明原因。
- 页面切换动画用 `horizontalSlideTransition`，毛玻璃用 `BackdropBlurView`，WebView 用 `createWebUiWebView` 创建，并设上 `WebUiChromeClient`（否则网页的上传按钮点了没反应）。
- Compose 里放 WebView 必须显式设 `MATCH_PARENT` 布局参数，否则 vh 恒为 0、页面空白。
- 跨 Activity 重建要保留的界面状态用 `rememberSaveable` 或放进 `MainViewModel` 的 `SavedStateHandle`。

### 注释

- 注释和文档用中文。写清触发条件、执行顺序和原因，例如“克隆 -> 改名 -> 读一次 HEAD 确认可用；原因是 proot 的 --link2symlink……”。
- 每个文件的主类或主函数上写一句“它是什么、什么时候用”。
- 来自真机排查的结论（NapCat 的 ErrCode、WebView 的 WRAP_CONTENT）要写在代码旁边，免得后人“优化”掉。

### 提交

- 日常开发在 `dev` 分支，`main` 只接收从 `dev` 合入的发布内容。
- 提交信息用 Conventional Commits：`类型(作用域): 中文说明`，正文用中文写改了什么、为什么。
- 单个提交保持聚焦，增删合计建议不超过 800 行；大的重构拆成几步，每一步都能编译、测试通过。
- 不要把个人信息（姓名、昵称、账号、本机路径、设备序列号）写进任何受版本管理的文件。
- 用户可见的变化写进 `CHANGELOG.md` 的 `## [Unreleased]`。
