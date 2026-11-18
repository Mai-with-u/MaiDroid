-- MaiDroid 远程公告、6 位求助码诊断快照与匿名遥测表结构（Cloudflare D1 / SQLite）

-- 单行保存当前生效的远程清单（公告 + 动态排障热规则）及已签名信封
CREATE TABLE IF NOT EXISTS manifest_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    signed_envelope_json TEXT NOT NULL,
    etag TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 用户主动点击“生成求助码”上传的脱敏诊断快照（默认保留 7 天自动清理）
CREATE TABLE IF NOT EXISTS diag_snapshots (
    code TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    app_version TEXT NOT NULL,
    device TEXT NOT NULL,
    sdk_int INTEGER NOT NULL,
    error_code TEXT,
    summary_json TEXT NOT NULL,
    payload_gzip BLOB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_diag_expires ON diag_snapshots (expires_at);

-- 无感匿名运行统计（仅存枚举与版本号，不含任何日志原文或个人标识，默认保留 30 天）
CREATE TABLE IF NOT EXISTS telemetry_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    install_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    app_version TEXT NOT NULL,
    manufacturer TEXT NOT NULL,
    model TEXT NOT NULL,
    sdk_int INTEGER NOT NULL,
    oobe_step TEXT,
    setup_complete INTEGER NOT NULL,
    active_qq_client TEXT,
    error_code TEXT,
    last_exit_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_telemetry_created ON telemetry_events (created_at);

-- 基于加盐哈希 IP 的滑动窗口频控表（不落盘明文 IP）
CREATE TABLE IF NOT EXISTS rate_limits (
    bucket_key TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    reset_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_reset ON rate_limits (reset_at);
