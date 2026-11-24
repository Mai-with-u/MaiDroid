-- 迁移 001：新增设备实时在线态与耗电温控状态表，并从历史 telemetry_events 回填每台设备的最新版本号

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

-- 对历史存量按每台设备最大 id 取最近一次上报状态幂等回填（is_online 置 0，仅保留最新版本号与设备画像）。
INSERT OR IGNORE INTO device_presence (
    install_id, first_seen_at, last_seen_at, last_online_at, is_online, last_hourly_event_at,
    app_version, version_code, build_type, manufacturer, model, sdk_int,
    oobe_step, setup_complete, active_qq_client, last_exit_reason
)
SELECT
    e.install_id,
    agg.first_seen_at,
    e.created_at,
    e.created_at,
    0,
    e.created_at,
    e.app_version,
    0,
    'release',
    e.manufacturer,
    e.model,
    e.sdk_int,
    e.oobe_step,
    e.setup_complete,
    e.active_qq_client,
    e.last_exit_reason
FROM telemetry_events e
INNER JOIN (
    SELECT install_id, MIN(created_at) AS first_seen_at, MAX(id) AS max_id
    FROM telemetry_events
    GROUP BY install_id
) agg ON e.id = agg.max_id;
