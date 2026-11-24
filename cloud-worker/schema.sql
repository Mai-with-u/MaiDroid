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

-- 设备实时在线状态、最新版本号（升级即覆写，自动迁出旧版计数）与耗电温控最新快照（每台设备严格一行）
CREATE TABLE IF NOT EXISTS device_presence (
    install_id TEXT PRIMARY KEY,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    last_online_at INTEGER NOT NULL,
    is_online INTEGER NOT NULL DEFAULT 1,
    last_hourly_event_at INTEGER NOT NULL DEFAULT 0,
    app_version TEXT NOT NULL,
    version_code INTEGER NOT NULL DEFAULT 0,
    build_type TEXT NOT NULL DEFAULT 'release',
    manufacturer TEXT NOT NULL,
    model TEXT NOT NULL,
    sdk_int INTEGER NOT NULL,
    oobe_step TEXT,
    setup_complete INTEGER NOT NULL,
    active_qq_client TEXT,
    last_exit_reason TEXT,
    battery_pct INTEGER,
    battery_temp_c REAL,
    peak_temp_c REAL,
    peak_temp_at INTEGER,
    battery_voltage_mv INTEGER,
    battery_charging INTEGER,
    charge_plug TEXT,
    battery_current_ma INTEGER,
    discharge_rate_pct_hr REAL,
    discharge_rate_at INTEGER,
    charge_rate_pct_hr REAL,
    charge_rate_at INTEGER,
    charging_drain INTEGER,
    last_charging_drain_at INTEGER,
    thermal_status INTEGER,
    app_cpu_pct REAL,
    screen_on INTEGER,
    screen_on_pct INTEGER,
    keep_screen_on INTEGER,
    cpu_affinity_pinned INTEGER,
    desktop_pet_running INTEGER,
    power_updated_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_device_presence_seen ON device_presence (last_seen_at);
CREATE INDEX IF NOT EXISTS idx_device_presence_online ON device_presence (is_online, last_online_at);
CREATE INDEX IF NOT EXISTS idx_device_presence_power ON device_presence (power_updated_at);

-- 基于加盐哈希 IP 的滑动窗口频控表（不落盘明文 IP）
CREATE TABLE IF NOT EXISTS rate_limits (
    bucket_key TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    reset_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_reset ON rate_limits (reset_at);

-- 自动报错聚合问题表（按“错误指纹 + 构建类型”唯一，彻底物理隔离正式版 release 与测试版 debug）
CREATE TABLE IF NOT EXISTS error_issues (
    fingerprint TEXT NOT NULL,
    build_type TEXT NOT NULL,
    category TEXT NOT NULL,
    error_code TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    first_version TEXT NOT NULL,
    last_version TEXT NOT NULL,
    total_count INTEGER NOT NULL DEFAULT 1,
    affected_installs INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (fingerprint, build_type)
);

CREATE INDEX IF NOT EXISTS idx_error_issues_query ON error_issues (build_type, status, last_seen_at DESC);

-- 自动报错受影响设备去重表（按匿名 install_id 统计独立受影响设备数，定期清理 30 天前记录）
CREATE TABLE IF NOT EXISTS error_issue_installs (
    fingerprint TEXT NOT NULL,
    build_type TEXT NOT NULL,
    install_id TEXT NOT NULL,
    last_seen_at INTEGER NOT NULL,
    PRIMARY KEY (fingerprint, build_type, install_id)
);

CREATE INDEX IF NOT EXISTS idx_error_issue_installs_seen ON error_issue_installs (last_seen_at);

-- 自动报错脱敏现场样本表（每个指纹最多保留 5 份代表性样本，避免高频报错打爆 D1 配额）
CREATE TABLE IF NOT EXISTS error_samples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fingerprint TEXT NOT NULL,
    build_type TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    app_version TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    manufacturer TEXT NOT NULL,
    model TEXT NOT NULL,
    sdk_int INTEGER NOT NULL,
    error_code TEXT NOT NULL,
    message TEXT NOT NULL,
    context_text TEXT NOT NULL,
    extra_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_error_samples_issue ON error_samples (fingerprint, build_type, created_at DESC);

-- 管理台成员账号（所有者在控制台创建；口令为 PBKDF2-SHA256 加盐哈希，首次登录必须改掉一次性初始口令）
CREATE TABLE IF NOT EXISTS admin_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    must_change INTEGER NOT NULL DEFAULT 1,
    disabled INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_login_at INTEGER
);

-- 管理台登录会话（只存会话令牌的 SHA-256，user_id = 0 表示 ADMIN_TOKEN 超级管理员）
CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_sessions_user ON admin_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_expires ON admin_sessions (expires_at);

-- 管理台审计日志（谁在何时查了哪个求助码、改了哪个报错、发布了哪版清单，保留 90 天）
CREATE TABLE IF NOT EXISTS admin_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    actor TEXT NOT NULL,
    role TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    detail TEXT
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created ON admin_audit (created_at DESC);