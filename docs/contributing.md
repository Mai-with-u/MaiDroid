
## 发布与签名

日常开发在 `dev` 分支，`main` 只接收从 `dev` 合入的内容。推送到 `main` 会触发发布流程（`.github/workflows/release.yml`）。日常调试用 `assembleDebug`，调试密钥由每台机器自动生成，不同机器构建出的 APK 签名不同，互相覆盖安装前需要先卸载。

### 发布一个新版本

1. 在 `dev` 上把本次更新的说明写进 `CHANGELOG.md` 最上面的 `## [Unreleased]` 一节。
2. 在 `dev` 上执行 `.\gradlew.bat bumpVersion -Pbump=<major|minor|patch>`，指定 a.b.c 里要 +1 的那一位：
   - `patch` 只加最后一位，例如 0.1.0 -> 0.1.1；
   - `minor` 加中间一位并把最后一位清零，例如 0.1.3 -> 0.2.0；
   - `major` 加第一位并把后两位都清零，例如 0.4.2 -> 1.0.0；
   - `versionCode` 固定 +1。

   任务会同步改写 `app/build.gradle.kts` 和 `CHANGELOG.md`（`Unreleased` 变成带版本号和日期的新一节，并在最上面补一个空的 `Unreleased`）。`Unreleased` 没有内容时任务会报错。
3. 提交，合入 `main` 并推送。
4. 流程读取 `CHANGELOG.md` 最上面带版本号的一节：对应的标签 `v<版本号>` 不存在时，构建、签名并创建 Release，说明就是这一节的正文，标签由流程创建；标签已存在则跳过，所以没有新增版本的推送不会重复发布。

版本号带连字符（例如 `0.2.0-rc1`）会按预发布处理，需要手工写进 `CHANGELOG.md` 和 `versionName`，`bumpVersion` 只处理 a.b.c 纯数字。在 Actions 页面手动运行该工作流只会产出试跑用的 APK，不创建 Release（需要本文件已经在 `main` 上）。