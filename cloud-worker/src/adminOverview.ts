// 管理台“遥测总览”聚合：一次请求拉齐大屏所需的 KPI、30 日健康条带、7×24 活跃热力、分布、耗电温控与组件状态。
// 触发：管理员解锁控制台后打开“01 总览”页，或每 60 秒自动刷新一次。
// 顺序：并发查询 device_presence 与 D1 明细表 -> 按北京时间补齐 30 天空白日 -> 折叠为前端直接可画的数组。
// 原因：设备级去重状态统一以 device_presence 为准，解决版本升级后旧版重复计数并支持实时在线与温耗观测。

import { ONLINE_WINDOW_SECONDS } from "./telemetryPresence.ts";

// 北京时间相对 UTC 的偏移秒数，所有按天、按小时的分桶都按北京时间切
export const CN_OFFSET_SECONDS = 8 * 3600;
export const HEALTH_DAYS = 30;
export const HEATMAP_DAYS = 28;

export interface DailyRow {
  d: number;
  installs: number;
  err_installs: number;
  events: number;
}

export interface DailyPoint {
  day: string;
  installs: number;
  errInstalls: number;
  events: number;
  errRate: number;
}

export interface LabelCount {
  label: string;
  count: number;
}

// 把“距纪元的北京时间天序号”换算为 MM-DD 标签
export function cnDayLabel(dayIndex: number): string {
  const date = new Date(dayIndex * 86400 * 1000);
  const mm = String(date.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(date.getUTCDate()).padStart(2, "0");
  return `${mm}-${dd}`;
}

export function cnDayIndex(epochSec: number): number {
  return Math.floor((epochSec + CN_OFFSET_SECONDS) / 86400);
}

// 补齐 30 天：没有心跳的日子也要占一根空柱，健康条带才能看出“断档”
export function fillDailySeries(rows: DailyRow[], todayIndex: number, days = HEALTH_DAYS): DailyPoint[] {
  const byDay = new Map<number, DailyRow>();
  for (const row of rows) byDay.set(Number(row.d), row);
  const out: DailyPoint[] = [];
  for (let i = days - 1; i >= 0; i--) {
    const d = todayIndex - i;
    const row = byDay.get(d);
    const installs = Number(row?.installs ?? 0);
    const errInstalls = Number(row?.err_installs ?? 0);
    out.push({
      day: cnDayLabel(d),
      installs,
      errInstalls,
      events: Number(row?.events ?? 0),
      // 当天上报过错误码的设备占比，用来给健康条带上色
      errRate: installs > 0 ? Math.round((errInstalls / installs) * 1000) / 1000 : 0,
    });
  }
  return out;
}

// 7×24 热力矩阵：行 = 周一..周日，列 = 0..23 时；SQL 里 wd 以周日为 0，这里转成周一为 0
export function buildHeatmapMatrix(rows: Array<{ wd: number; hr: number; cnt: number }>): number[][] {
  const matrix = Array.from({ length: 7 }, () => new Array<number>(24).fill(0));
  for (const row of rows) {
    const wd = Number(row.wd);
    const hr = Number(row.hr);
    if (wd < 0 || wd > 6 || hr < 0 || hr > 23) continue;
    const mondayFirst = (wd + 6) % 7;
    matrix[mondayFirst][hr] += Number(row.cnt) || 0;
  }
  return matrix;
}

// 组件状态：最近一次收到数据的时间决定“正常 / 空闲 / 未启用”
export function componentState(lastAt: number | null | undefined, nowSec: number, idleAfterSec: number): string {
  if (!lastAt) return "none";
  return nowSec - lastAt <= idleAfterSec ? "up" : "idle";
}

type Db = D1Database;

function round1(val: number | null | undefined): number | null {
  if (val === null || val === undefined || !Number.isFinite(Number(val))) return null;
  return Math.round(Number(val) * 10) / 10;
}

async function presenceShares(db: Db, column: string, since: number, limit = 8): Promise<LabelCount[]> {
  // column 只来自本文件白名单；按 device_presence 每设备最新一行统计，用户升级后自动从旧版本迁出。
  const res = await db
    .prepare(
      `SELECT ${column} AS label, COUNT(*) AS count FROM device_presence
       WHERE last_seen_at >= ? AND ${column} IS NOT NULL AND ${column} != ''
       GROUP BY ${column} ORDER BY count DESC LIMIT ${limit}`
    )
    .bind(since)
    .all<LabelCount>();
  return (res.results ?? []).map((r) => ({ label: String(r.label), count: Number(r.count) || 0 }));
}

async function onlineVersionShares(db: Db, sinceOnline: number, limit = 8): Promise<LabelCount[]> {
  const res = await db
    .prepare(
      `SELECT app_version AS label, COUNT(*) AS count FROM device_presence
       WHERE is_online = 1 AND last_online_at >= ? AND app_version IS NOT NULL AND app_version != ''
       GROUP BY app_version ORDER BY count DESC LIMIT ${limit}`
    )
    .bind(sinceOnline)
    .all<LabelCount>();
  return (res.results ?? []).map((r) => ({ label: String(r.label), count: Number(r.count) || 0 }));
}

export async function buildAdminOverview(db: Db, nowSec: number) {
  const sinceOnline = nowSec - ONLINE_WINDOW_SECONDS;
  const since24h = nowSec - 86400;
  const since7d = nowSec - 7 * 86400;
  const todayIndex = cnDayIndex(nowSec);
  // 30 日窗口从“29 天前的北京时间零点”开始，保证第一根柱子是完整的一天
  const since30d = (todayIndex - (HEALTH_DAYS - 1)) * 86400 - CN_OFFSET_SECONDS;
  const sinceHeat = nowSec - HEATMAP_DAYS * 86400;

  const [
    onlineNowRow,
    active24h,
    totals7d,
    totals30d,
    diag7d,
    dailyRes,
    heatRes,
    issueCats,
    issueStatus,
    manifestRow,
    lastPresence,
    lastTelemetry,
    lastDiag,
    lastIssue,
    manufacturers,
    versions,
    onlineVersions,
    qqClients,
    oobeSteps,
    sdks,
    topErrors,
    topExits,
    powerSummaryRow,
    tempBucketsRow,
    hotModelsRes,
  ] = await Promise.all([
    db.prepare("SELECT COUNT(*) AS uniq FROM device_presence WHERE is_online = 1 AND last_online_at >= ?")
      .bind(sinceOnline).first<{ uniq: number }>(),
    db.prepare("SELECT COUNT(*) AS uniq FROM device_presence WHERE last_seen_at >= ?")
      .bind(since24h).first<{ uniq: number }>(),
    db.prepare(
      `SELECT COUNT(*) AS uniq,
              COALESCE(SUM(CASE WHEN setup_complete = 1 THEN 1 ELSE 0 END), 0) AS done
       FROM device_presence WHERE last_seen_at >= ?`
    ).bind(since7d).first<{ uniq: number; done: number }>(),
    db.prepare("SELECT COUNT(*) AS uniq FROM device_presence WHERE last_seen_at >= ?")
      .bind(since30d).first<{ uniq: number }>(),
    db.prepare("SELECT COUNT(*) AS cnt FROM diag_snapshots WHERE created_at >= ?")
      .bind(since7d).first<{ cnt: number }>(),
    db.prepare(
      `SELECT CAST((created_at + ${CN_OFFSET_SECONDS}) / 86400 AS INTEGER) AS d,
              COUNT(DISTINCT install_id) AS installs,
              COUNT(DISTINCT CASE WHEN error_code IS NOT NULL AND error_code != '' THEN install_id END) AS err_installs,
              COUNT(*) AS events
       FROM telemetry_events WHERE created_at >= ? GROUP BY d`
    ).bind(since30d).all<DailyRow>(),
    // 1970-01-01 是周四，(天序号 + 4) % 7 得到以周日为 0 的星期
    db.prepare(
      `SELECT CAST(((created_at + ${CN_OFFSET_SECONDS}) / 86400 + 4) % 7 AS INTEGER) AS wd,
              CAST(((created_at + ${CN_OFFSET_SECONDS}) % 86400) / 3600 AS INTEGER) AS hr,
              COUNT(*) AS cnt
       FROM telemetry_events WHERE created_at >= ? GROUP BY wd, hr`
    ).bind(sinceHeat).all<{ wd: number; hr: number; cnt: number }>(),
    db.prepare(
      `SELECT category, COUNT(*) AS cnt, SUM(affected_installs) AS installs FROM error_issues
       WHERE build_type = 'release' AND status = 'open' GROUP BY category`
    ).all<{ category: string; cnt: number; installs: number }>(),
    db.prepare(
      "SELECT status, COUNT(*) AS cnt FROM error_issues WHERE build_type = 'release' GROUP BY status"
    ).all<{ status: string; cnt: number }>(),
    db.prepare("SELECT version, updated_at, payload_json FROM manifest_config WHERE id = 1")
      .first<{ version: number; updated_at: number; payload_json: string }>(),
    db.prepare("SELECT MAX(last_seen_at) AS at FROM device_presence").first<{ at: number | null }>(),
    db.prepare("SELECT MAX(created_at) AS at FROM telemetry_events").first<{ at: number | null }>(),
    db.prepare("SELECT MAX(created_at) AS at FROM diag_snapshots").first<{ at: number | null }>(),
    db.prepare("SELECT MAX(last_seen_at) AS at FROM error_issues").first<{ at: number | null }>(),
    presenceShares(db, "manufacturer", since7d),
    presenceShares(db, "app_version", since7d),
    onlineVersionShares(db, sinceOnline),
    presenceShares(db, "active_qq_client", since7d),
    presenceShares(db, "oobe_step", since7d),
    presenceShares(db, "sdk_int", since7d),
    db.prepare(
      `SELECT error_code AS label, COUNT(*) AS count FROM telemetry_events
       WHERE created_at >= ? AND error_code IS NOT NULL AND error_code != ''
       GROUP BY error_code ORDER BY count DESC LIMIT 8`
    ).bind(since7d).all<LabelCount>(),
    db.prepare(
      `SELECT last_exit_reason AS label, COUNT(*) AS count FROM telemetry_events
       WHERE created_at >= ? AND last_exit_reason IS NOT NULL AND last_exit_reason != ''
       GROUP BY last_exit_reason ORDER BY count DESC LIMIT 8`
    ).bind(since7d).all<LabelCount>(),
    db.prepare(
      `SELECT COUNT(*) AS reported24h,
              COALESCE(SUM(CASE WHEN is_online = 1 AND last_online_at >= ? THEN 1 ELSE 0 END), 0) AS onlineReported,
              COALESCE(SUM(CASE WHEN battery_charging = 1 THEN 1 ELSE 0 END), 0) AS chargingCount,
              COALESCE(SUM(CASE WHEN last_charging_drain_at >= ? OR charging_drain = 1 THEN 1 ELSE 0 END), 0) AS chargingDrainCount,
              COALESCE(SUM(CASE WHEN COALESCE(peak_temp_c, battery_temp_c) >= 40.0 THEN 1 ELSE 0 END), 0) AS hotCount,
              AVG(battery_temp_c) AS avgTempC,
              MAX(COALESCE(peak_temp_c, battery_temp_c)) AS maxTempC,
              AVG(CASE WHEN discharge_rate_at >= ? THEN discharge_rate_pct_hr END) AS avgDischargePctHr,
              AVG(app_cpu_pct) AS avgCpuPct,
              COALESCE(SUM(CASE WHEN keep_screen_on = 1 THEN 1 ELSE 0 END), 0) AS keepScreenOnCount,
              COALESCE(SUM(CASE WHEN cpu_affinity_pinned = 1 THEN 1 ELSE 0 END), 0) AS cpuPinnedCount,
              COALESCE(SUM(CASE WHEN cpu_affinity_pinned IS NOT NULL THEN 1 ELSE 0 END), 0) AS cpuPinKnownCount,
              COALESCE(SUM(CASE WHEN desktop_pet_running = 1 THEN 1 ELSE 0 END), 0) AS desktopPetCount
       FROM device_presence WHERE power_updated_at >= ?`
    ).bind(sinceOnline, since24h, since24h, since24h).first<Record<string, number | null>>(),
    db.prepare(
      `SELECT COALESCE(SUM(CASE WHEN t < 35.0 THEN 1 ELSE 0 END), 0) AS cool,
              COALESCE(SUM(CASE WHEN t >= 35.0 AND t < 40.0 THEN 1 ELSE 0 END), 0) AS warm,
              COALESCE(SUM(CASE WHEN t >= 40.0 AND t < 43.0 THEN 1 ELSE 0 END), 0) AS hot,
              COALESCE(SUM(CASE WHEN t >= 43.0 THEN 1 ELSE 0 END), 0) AS severe
       FROM (
         SELECT COALESCE(peak_temp_c, battery_temp_c) AS t
         FROM device_presence
         WHERE power_updated_at >= ? AND COALESCE(peak_temp_c, battery_temp_c) IS NOT NULL
       )`
    ).bind(since24h).first<Record<string, number | null>>(),
    db.prepare(
      `SELECT manufacturer,
              model,
              COUNT(*) AS devices,
              COALESCE(SUM(CASE WHEN last_charging_drain_at >= ? OR charging_drain = 1 THEN 1 ELSE 0 END), 0) AS chargingDrainCount,
              COALESCE(SUM(CASE WHEN COALESCE(peak_temp_c, battery_temp_c) >= 40.0 THEN 1 ELSE 0 END), 0) AS hotCount,
              AVG(battery_temp_c) AS avgTempC,
              MAX(COALESCE(peak_temp_c, battery_temp_c)) AS maxTempC,
              AVG(CASE WHEN discharge_rate_at >= ? THEN discharge_rate_pct_hr END) AS avgDischargePctHr,
              MIN(CASE WHEN charge_rate_at >= ? THEN charge_rate_pct_hr END) AS minChargeRatePctHr,
              AVG(app_cpu_pct) AS avgCpuPct,
              COALESCE(SUM(CASE WHEN keep_screen_on = 1 THEN 1 ELSE 0 END), 0) AS keepScreenOnCount,
              COALESCE(SUM(CASE WHEN cpu_affinity_pinned = 1 THEN 1 ELSE 0 END), 0) AS cpuPinnedCount,
              COALESCE(SUM(CASE WHEN cpu_affinity_pinned IS NOT NULL THEN 1 ELSE 0 END), 0) AS cpuPinKnownCount
       FROM device_presence
       WHERE power_updated_at >= ?
       GROUP BY manufacturer, model
       ORDER BY chargingDrainCount DESC, hotCount DESC, COALESCE(maxTempC, -999) DESC, COALESCE(avgCpuPct, -1) DESC
       LIMIT 8`
    ).bind(since24h, since24h, since24h, since24h).all<Record<string, unknown>>(),
  ]);

  const latestTelemetryAt = Math.max(Number(lastPresence?.at || 0), Number(lastTelemetry?.at || 0)) || null;

  const openByCategory: Record<string, { issues: number; installs: number }> = {};
  let openIssues = 0;
  let openInstalls = 0;
  for (const row of issueCats.results ?? []) {
    const issues = Number(row.cnt) || 0;
    const installs = Number(row.installs) || 0;
    openByCategory[row.category] = { issues, installs };
    openIssues += issues;
    openInstalls += installs;
  }
  const releaseStatus: Record<string, number> = { open: 0, resolved: 0, ignored: 0 };
  for (const row of issueStatus.results ?? []) releaseStatus[row.status] = Number(row.cnt) || 0;

  // 清单里当前挂着多少条公告与热规则，组件状态卡直接展示
  let annCount = 0;
  let hintCount = 0;
  if (manifestRow?.payload_json) {
    try {
      const payload = JSON.parse(manifestRow.payload_json) as { announcements?: unknown[]; failureHints?: unknown[] };
      annCount = Array.isArray(payload.announcements) ? payload.announcements.length : 0;
      hintCount = Array.isArray(payload.failureHints) ? payload.failureHints.length : 0;
    } catch {
      // 清单 JSON 损坏时只影响计数展示，不阻断总览
    }
  }

  return {
    generatedAt: nowSec,
    kpi: {
      onlineNow: Number(onlineNowRow?.uniq) || 0,
      active24h: Number(active24h?.uniq) || 0,
      installs7d: Number(totals7d?.uniq) || 0,
      completed7d: Number(totals7d?.done) || 0,
      installs30d: Number(totals30d?.uniq) || 0,
      diag7d: Number(diag7d?.cnt) || 0,
      openIssues,
      openInstalls,
    },
    daily: fillDailySeries(dailyRes.results ?? [], todayIndex),
    heatmap: buildHeatmapMatrix(heatRes.results ?? []),
    openByCategory,
    releaseStatus,
    components: {
      manifest: {
        state: manifestRow ? "up" : "none",
        version: manifestRow?.version ?? 0,
        updatedAt: manifestRow?.updated_at ?? null,
        announcements: annCount,
        failureHints: hintCount,
      },
      telemetry: { state: componentState(latestTelemetryAt, nowSec, 6 * 3600), lastAt: latestTelemetryAt },
      errorReport: { state: componentState(lastIssue?.at, nowSec, 3 * 86400), lastAt: lastIssue?.at ?? null },
      diag: { state: componentState(lastDiag?.at, nowSec, 7 * 86400), lastAt: lastDiag?.at ?? null },
    },
    shares: { manufacturers, versions, onlineVersions, qqClients, oobeSteps, sdks },
    power: {
      summary: {
        reported24h: Number(powerSummaryRow?.reported24h) || 0,
        onlineReported: Number(powerSummaryRow?.onlineReported) || 0,
        chargingCount: Number(powerSummaryRow?.chargingCount) || 0,
        chargingDrainCount: Number(powerSummaryRow?.chargingDrainCount) || 0,
        hotCount: Number(powerSummaryRow?.hotCount) || 0,
        avgTempC: round1(powerSummaryRow?.avgTempC),
        maxTempC: round1(powerSummaryRow?.maxTempC),
        avgDischargePctHr: round1(powerSummaryRow?.avgDischargePctHr),
        avgCpuPct: round1(powerSummaryRow?.avgCpuPct),
        keepScreenOnCount: Number(powerSummaryRow?.keepScreenOnCount) || 0,
        cpuPinnedCount: Number(powerSummaryRow?.cpuPinnedCount) || 0,
        cpuPinKnownCount: Number(powerSummaryRow?.cpuPinKnownCount) || 0,
        desktopPetCount: Number(powerSummaryRow?.desktopPetCount) || 0,
      },
      tempBuckets: [
        { label: "<35°C", count: Number(tempBucketsRow?.cool) || 0 },
        { label: "35-39.9°C", count: Number(tempBucketsRow?.warm) || 0 },
        { label: "40-42.9°C", count: Number(tempBucketsRow?.hot) || 0 },
        { label: "≥43°C", count: Number(tempBucketsRow?.severe) || 0 },
      ],
      hotModels: (hotModelsRes.results ?? []).map((r) => ({
        label: `${String(r.manufacturer ?? "")} ${String(r.model ?? "")}`.trim() || "unknown",
        devices: Number(r.devices) || 0,
        chargingDrainCount: Number(r.chargingDrainCount) || 0,
        hotCount: Number(r.hotCount) || 0,
        avgTempC: round1(r.avgTempC as number | null | undefined),
        maxTempC: round1(r.maxTempC as number | null | undefined),
        avgDischargePctHr: round1(r.avgDischargePctHr as number | null | undefined),
        minChargeRatePctHr: round1(r.minChargeRatePctHr as number | null | undefined),
        avgCpuPct: round1(r.avgCpuPct as number | null | undefined),
        keepScreenOnCount: Number(r.keepScreenOnCount) || 0,
        cpuPinnedCount: Number(r.cpuPinnedCount) || 0,
        cpuPinKnownCount: Number(r.cpuPinKnownCount) || 0,
      })),
    },
    topErrors: topErrors.results ?? [],
    topExitReasons: topExits.results ?? [],
  };
}
