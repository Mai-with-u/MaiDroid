// 设备实时在线状态、版本升级迁出旧版计数以及电池温耗遥测的状态合并纯逻辑。
// 触发：POST /v1/telemetry 收到心跳、状态事件或离线通知时调用。
// 顺序：校验并裁剪电源字段 -> 对比上一条 device_presence 记录 -> 算出更新后的当前行与是否落入明细表。
// 原因：将版本覆盖去重、24h 峰值/掉电速率保持及 1 小时热力采样节流收敛为纯函数，方便单测与真实 SQLite 验证。

export const ONLINE_WINDOW_SECONDS = 15 * 60;
export const HOURLY_EVENT_INTERVAL_SECONDS = 3600;
export const DEVICE_PRESENCE_TTL_SECONDS = 90 * 86400;
const DAY_SECONDS = 86400;
const VALID_CHARGE_PLUGS = new Set(["none", "ac", "usb", "wireless", "dock", "other"]);

export interface PowerTelemetryInput {
  batteryPct: number | null;
  batteryTempC: number | null;
  batteryVoltageMv: number | null;
  batteryCharging: boolean | null;
  chargePlug: string | null;
  batteryCurrentMa: number | null;
  dischargeRatePctPerHr: number | null;
  chargeRatePctPerHr: number | null;
  chargingDrain: boolean | null;
  thermalStatus: number | null;
  appCpuPct: number | null;
  screenOn: boolean | null;
  screenOnPct: number | null;
  keepScreenOn: boolean | null;
  cpuAffinityPinned: boolean | null;
  desktopPetRunning: boolean | null;
}

export interface ExistingPresenceRow {
  install_id: string;
  first_seen_at: number;
  last_seen_at: number;
  last_online_at: number;
  is_online: number;
  last_hourly_event_at: number;
  app_version: string;
  version_code: number;
  build_type: string;
  manufacturer: string;
  model: string;
  sdk_int: number;
  oobe_step: string | null;
  setup_complete: number;
  active_qq_client: string | null;
  last_exit_reason: string | null;
  battery_pct: number | null;
  battery_temp_c: number | null;
  peak_temp_c: number | null;
  peak_temp_at: number | null;
  battery_voltage_mv: number | null;
  battery_charging: number | null;
  charge_plug: string | null;
  battery_current_ma: number | null;
  discharge_rate_pct_hr: number | null;
  discharge_rate_at: number | null;
  charge_rate_pct_hr: number | null;
  charge_rate_at: number | null;
  charging_drain: number | null;
  last_charging_drain_at: number | null;
  thermal_status: number | null;
  app_cpu_pct: number | null;
  screen_on: number | null;
  screen_on_pct: number | null;
  keep_screen_on: number | null;
  cpu_affinity_pinned: number | null;
  desktop_pet_running: number | null;
  power_updated_at: number | null;
}

export interface IncomingTelemetryInput {
  installId: string;
  eventType: string;
  appVersion: string;
  versionCode: number;
  buildType: string;
  manufacturer: string;
  model: string;
  sdkInt: number;
  oobeStep: string | null;
  setupComplete: boolean;
  activeQqClient: string | null;
  lastExitReason: string | null;
  power: PowerTelemetryInput | null;
}

export interface MergedPresenceResult {
  row: ExistingPresenceRow;
  shouldInsertTelemetryEvent: boolean;
}

function clampInt(val: unknown, min: number, max: number): number | null {
  if (typeof val !== "number" || !Number.isFinite(val)) return null;
  const rounded = Math.trunc(val);
  return rounded >= min && rounded <= max ? rounded : null;
}

function clampOneDecimal(val: unknown, min: number, max: number): number | null {
  if (typeof val !== "number" || !Number.isFinite(val)) return null;
  if (val < min || val > max) return null;
  return Math.round(val * 10) / 10;
}

function optBool(val: unknown): boolean | null {
  return typeof val === "boolean" ? val : null;
}

function boolToSqlite(val: boolean | null): number | null {
  if (val === null) return null;
  return val ? 1 : 0;
}

/** 白名单校验并裁剪客户端上报的电源/温控字段；全空或类型不合法时返回 null。 */
export function parsePowerTelemetry(raw: unknown): PowerTelemetryInput | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;
  const chargePlug =
    typeof obj.chargePlug === "string" && VALID_CHARGE_PLUGS.has(obj.chargePlug.trim())
      ? obj.chargePlug.trim()
      : null;
  const parsed: PowerTelemetryInput = {
    batteryPct: clampInt(obj.batteryPct, 0, 100),
    batteryTempC: clampOneDecimal(obj.batteryTempC, -20, 120),
    batteryVoltageMv: clampInt(obj.batteryVoltageMv, 1000, 20000),
    batteryCharging: optBool(obj.batteryCharging),
    chargePlug,
    batteryCurrentMa: clampInt(obj.batteryCurrentMa, -15000, 15000),
    dischargeRatePctPerHr: clampOneDecimal(obj.dischargeRatePctPerHr, 0, 300),
    chargeRatePctPerHr: clampOneDecimal(obj.chargeRatePctPerHr, -300, 300),
    chargingDrain: optBool(obj.chargingDrain),
    thermalStatus: clampInt(obj.thermalStatus, 0, 6),
    appCpuPct: clampOneDecimal(obj.appCpuPct, 0, 1600),
    screenOn: optBool(obj.screenOn),
    screenOnPct: clampInt(obj.screenOnPct, 0, 100),
    keepScreenOn: optBool(obj.keepScreenOn),
    cpuAffinityPinned: optBool(obj.cpuAffinityPinned),
    desktopPetRunning: optBool(obj.desktopPetRunning),
  };
  const hasAnyValue = Object.values(parsed).some((v) => v !== null);
  return hasAnyValue ? parsed : null;
}

/** 合并旧设备记录与最新遥测：即时切换最新版本、维护 24h 高温/速率快照并按需节流明细表。 */
export function mergePresenceState(
  prev: ExistingPresenceRow | null,
  incoming: IncomingTelemetryInput,
  nowSec: number
): MergedPresenceResult {
  const isOfflineEvent = incoming.eventType === "offline";
  const isOnline = isOfflineEvent ? 0 : 1;
  const lastOnlineAt = isOfflineEvent ? (prev?.last_online_at ?? nowSec) : nowSec;
  const versionChanged = prev !== null && prev.app_version !== incoming.appVersion;

  let shouldInsertTelemetryEvent = true;
  if (isOfflineEvent) {
    shouldInsertTelemetryEvent = false;
  } else if (incoming.eventType === "online_heartbeat") {
    shouldInsertTelemetryEvent =
      prev === null ||
      versionChanged ||
      nowSec - (prev.last_hourly_event_at || 0) >= HOURLY_EVENT_INTERVAL_SECONDS;
  }

  const p = incoming.power;
  // 24 小时温度峰值与独立充/放电速率：避免插电覆盖掉刚测出来的放电速率，也避免一次普通心跳冲掉当日高温峰值。
  let peakTempC: number | null =
    prev?.peak_temp_c != null && prev.peak_temp_at != null && nowSec - prev.peak_temp_at < DAY_SECONDS
      ? prev.peak_temp_c
      : null;
  let peakTempAt: number | null = peakTempC !== null ? prev!.peak_temp_at : null;
  if (p?.batteryTempC !== null && p?.batteryTempC !== undefined) {
    if (peakTempC === null || p.batteryTempC >= peakTempC) {
      peakTempC = p.batteryTempC;
      peakTempAt = nowSec;
    }
  }

  let dischargeRatePctHr: number | null =
    prev?.discharge_rate_pct_hr != null &&
    prev.discharge_rate_at != null &&
    nowSec - prev.discharge_rate_at < DAY_SECONDS
      ? prev.discharge_rate_pct_hr
      : null;
  let dischargeRateAt: number | null = dischargeRatePctHr !== null ? prev!.discharge_rate_at : null;
  if (p?.dischargeRatePctPerHr !== null && p?.dischargeRatePctPerHr !== undefined) {
    dischargeRatePctHr = p.dischargeRatePctPerHr;
    dischargeRateAt = nowSec;
  }

  let chargeRatePctHr: number | null =
    prev?.charge_rate_pct_hr != null &&
    prev.charge_rate_at != null &&
    nowSec - prev.charge_rate_at < DAY_SECONDS
      ? prev.charge_rate_pct_hr
      : null;
  let chargeRateAt: number | null = chargeRatePctHr !== null ? prev!.charge_rate_at : null;
  if (p?.chargeRatePctPerHr !== null && p?.chargeRatePctPerHr !== undefined) {
    chargeRatePctHr = p.chargeRatePctPerHr;
    chargeRateAt = nowSec;
  }

  const lastChargingDrainAt =
    p?.chargingDrain === true
      ? nowSec
      : prev?.last_charging_drain_at != null && nowSec - prev.last_charging_drain_at < DAY_SECONDS
        ? prev.last_charging_drain_at
        : null;

  const row: ExistingPresenceRow = {
    install_id: incoming.installId,
    first_seen_at: prev?.first_seen_at ?? nowSec,
    last_seen_at: nowSec,
    last_online_at: lastOnlineAt,
    is_online: isOnline,
    last_hourly_event_at: shouldInsertTelemetryEvent ? nowSec : (prev?.last_hourly_event_at ?? nowSec),
    app_version: incoming.appVersion,
    version_code: incoming.versionCode > 0 ? incoming.versionCode : (prev?.version_code ?? 0),
    build_type: incoming.buildType || prev?.build_type || "release",
    manufacturer: incoming.manufacturer,
    model: incoming.model,
    sdk_int: incoming.sdkInt,
    oobe_step: incoming.oobeStep ?? prev?.oobe_step ?? null,
    setup_complete: incoming.setupComplete ? 1 : 0,
    active_qq_client: incoming.activeQqClient ?? prev?.active_qq_client ?? null,
    last_exit_reason: incoming.lastExitReason ?? prev?.last_exit_reason ?? null,
    battery_pct: p ? p.batteryPct : (prev?.battery_pct ?? null),
    battery_temp_c: p ? p.batteryTempC : (prev?.battery_temp_c ?? null),
    peak_temp_c: peakTempC,
    peak_temp_at: peakTempAt,
    battery_voltage_mv: p ? p.batteryVoltageMv : (prev?.battery_voltage_mv ?? null),
    battery_charging: p ? boolToSqlite(p.batteryCharging) : (prev?.battery_charging ?? null),
    charge_plug: p ? p.chargePlug : (prev?.charge_plug ?? null),
    battery_current_ma: p ? p.batteryCurrentMa : (prev?.battery_current_ma ?? null),
    discharge_rate_pct_hr: dischargeRatePctHr,
    discharge_rate_at: dischargeRateAt,
    charge_rate_pct_hr: chargeRatePctHr,
    charge_rate_at: chargeRateAt,
    charging_drain: p ? boolToSqlite(p.chargingDrain) : (prev?.charging_drain ?? null),
    last_charging_drain_at: lastChargingDrainAt,
    thermal_status: p ? p.thermalStatus : (prev?.thermal_status ?? null),
    app_cpu_pct: p ? p.appCpuPct : (prev?.app_cpu_pct ?? null),
    screen_on: p ? boolToSqlite(p.screenOn) : (prev?.screen_on ?? null),
    screen_on_pct: p ? p.screenOnPct : (prev?.screen_on_pct ?? null),
    keep_screen_on: p ? boolToSqlite(p.keepScreenOn) : (prev?.keep_screen_on ?? null),
    cpu_affinity_pinned: p ? boolToSqlite(p.cpuAffinityPinned) : (prev?.cpu_affinity_pinned ?? null),
    desktop_pet_running: p ? boolToSqlite(p.desktopPetRunning) : (prev?.desktop_pet_running ?? null),
    power_updated_at: p ? nowSec : (prev?.power_updated_at ?? null),
  };

  return { row, shouldInsertTelemetryEvent };
}
