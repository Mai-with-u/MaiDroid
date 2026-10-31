# CNB 发布镜像

工作流将 GitHub 正式版本的 APK、其他附件和发布说明同步到 `https://cnb.cool/Mai-with-u/MaiDroid`，并生成 `cnb-updates/latest.json`。只同步稳定版本，要求至少一个 APK；不会覆盖不一致的已有附件或 CNB 主分支。

启用前需创建公开的 CNB 目标仓库，并在 GitHub 仓库 Actions Secrets 设置 `CNB_TOKEN`，令牌需拥有目标仓库的 `repo-code:rw` 和 `repo-release:rw` 权限。不要提交令牌。

推送 main 触发的 Release 工作流成功结束后会同步最新正式版本；其他方式发布的正式 Release 也会触发同步。Actions 中可手动运行 `Sync release to CNB`，留空 tag 同步最新正式版，或填写历史正式版 tag。同步完成后会验证附件可公开下载；补同步旧版本不会将其标为最新。工作流不会更改 Android 客户端的更新来源。

运行本地测试：`python -m unittest discover -s scripts/tests -p test_cnb_release_sync.py`。测试不需要令牌，也不会上传文件。
