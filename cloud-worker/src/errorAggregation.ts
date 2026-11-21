// 自动报错上报校验、版本比对、问题状态迁移、样本抽样策略与维度汇总（纯函数模块，方便 Worker 复用与单元测试）。

export const MAX_ERROR_REPORT_BYTES = 24 * 1024;
export const MAX_ERROR_CONTEXT_CHARS = 16 * 1024;
export const MAX_SAMPLES_PER_ISSUE = 5;

export type ErrorCategory = "crash" | "install" | "maibot_boot";
export type IssueStatus = "open" | "resolved" | "ignored";

export interface ErrorReportExtra {
  oobeStep?: string | null;
  setupComplete?: boolean | null;
  activeQqClient?: string | null;
  availableStorageMb?: number | null;
  availableMemMb?: number | null;
  lastExitReason?: string | null;
}

export interface ValidatedErrorReport {
  installId: string;
  fingerprint: string;
  buildType: string;
  category: ErrorCategory;
  errorCode: string;
  appVersion: string;
  versionCode: number;
  manufacturer: string;
  model: string;
  sdkInt: number;
  title: string;
  message: string;
  contextText: string;
  extra: ErrorReportExtra;
}

export interface SampleMetadata {
  app_version: string;
  manufacturer: string;
  sdk_int?: number;
}

export interface DistributionBucket {
  label: string;
  count: number;
}

export interface SampleDistribution {
  versions: DistributionBucket[];
  manufacturers: DistributionBucket[];
  sdks: DistributionBucket[];
}

const FINGERPRINT_RE = /^[a-f0-9]{16,64}$/;
const BUILD_TYPE_RE = /^[a-z0-9_-]{1,16}$/;
const ALLOWED_CATEGORIES = new Set<ErrorCategory>(["crash", "install", "maibot_boot"]);
const ALLOWED_STATUSES = new Set<IssueStatus>(["open", "resolved", "ignored"]);

function safeStr(val: unknown, maxLen: number): string | null {
  if (typeof val !== "string") return null;
  const trimmed = val.trim();
  return trimmed ? trimmed.slice(0, maxLen) : null;
}

function safeInt(val: unknown): number {
  return typeof val === "number" && Number.isFinite(val) ? Math.trunc(val) : 0;
}

function safeOptInt(val: unknown): number | null {
  return typeof val === "number" && Number.isFinite(val) ? Math.trunc(val) : null;
}

/** 校验并规范化客户端自动报错 JSON；缺少核心字段或格式不合法时返回 null。 */
export function validateErrorReportPayload(raw: unknown): ValidatedErrorReport | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;

  const installId = safeStr(obj.installId, 64);
  const fingerprintRaw = typeof obj.fingerprint === "string" ? obj.fingerprint.trim().toLowerCase() : "";
  const categoryRaw = typeof obj.category === "string" ? obj.category.trim() : "";
  const errorCode = safeStr(obj.errorCode, 48);
  const appVersion = safeStr(obj.appVersion, 32);
  const title = safeStr(obj.title, 180);

  if (!installId || !errorCode || !appVersion || !title) return null;
  if (!FINGERPRINT_RE.test(fingerprintRaw)) return null;
  if (!ALLOWED_CATEGORIES.has(categoryRaw as ErrorCategory)) return null;

  const buildTypeRaw = typeof obj.buildType === "string" ? obj.buildType.trim().toLowerCase() : "release";
  const buildType = BUILD_TYPE_RE.test(buildTypeRaw) ? buildTypeRaw : "release";

  const message = safeStr(obj.message, 300) ?? title;
  // contextText 允许保留前导缩进，不能 trim 掉栈帧格式，只限制总长度
  const contextText = typeof obj.contextText === "string"
    ? obj.contextText.slice(0, MAX_ERROR_CONTEXT_CHARS)
    : "";

  const extraObj = obj.extra && typeof obj.extra === "object" && !Array.isArray(obj.extra)
    ? (obj.extra as Record<string, unknown>)
    : {};
  const extra: ErrorReportExtra = {};
  const oobeStep = safeStr(extraObj.oobeStep, 32);
  if (oobeStep !== null) extra.oobeStep = oobeStep;
  if (typeof extraObj.setupComplete === "boolean") extra.setupComplete = extraObj.setupComplete;
  const activeQqClient = safeStr(extraObj.activeQqClient, 24);
  if (activeQqClient !== null) extra.activeQqClient = activeQqClient;
  const availableStorageMb = safeOptInt(extraObj.availableStorageMb);
  if (availableStorageMb !== null) extra.availableStorageMb = availableStorageMb;
  const availableMemMb = safeOptInt(extraObj.availableMemMb);
  if (availableMemMb !== null) extra.availableMemMb = availableMemMb;
  const lastExitReason = safeStr(extraObj.lastExitReason, 96);
  if (lastExitReason !== null) extra.lastExitReason = lastExitReason;

  return {
    installId,
    fingerprint: fingerprintRaw,
    buildType,
    category: categoryRaw as ErrorCategory,
    errorCode,
    appVersion,
    versionCode: safeInt(obj.versionCode),
    manufacturer: safeStr(obj.manufacturer, 48) || "unknown",
    model: safeStr(obj.model, 64) || "unknown",
    sdkInt: safeInt(obj.sdkInt),
    title,
    message,
    contextText,
    extra,
  };
}

export function normalizeIssueStatus(raw: unknown): IssueStatus | null {
  if (typeof raw !== "string") return null;
  const clean = raw.trim().toLowerCase();
  return ALLOWED_STATUSES.has(clean as IssueStatus) ? (clean as IssueStatus) : null;
}

/** 按点分数字段比较应用版本（如 0.2.10 > 0.2.5），忽略前导 v 或构建后缀。 */
export function compareAppVersion(a: string, b: string): number {
  const parse = (ver: string) =>
    ver
      .trim()
      .replace(/^v/i, "")
      .split(/[-+]/)[0]
      .split(".")
      .map((part) => {
        const num = Number.parseInt(part, 10);
        return Number.isFinite(num) ? num : 0;
      });
  const pa = parse(a);
  const pb = parse(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const va = pa[i] ?? 0;
    const vb = pb[i] ?? 0;
    if (va !== vb) return va > vb ? 1 : -1;
  }
  return 0;
}

/**
 * 收到新一次同类报错时的状态迁移规则：
 * - 已忽略（ignored）保持忽略；
 * - 已修复（resolved）若后续出现更高版本的上报，则自动重开（open），提醒开发者回归；
 * - 仍在旧版本上上报则保持 resolved，避免老版本用户刷屏。
 */
export function resolveNextIssueStatus(
  existingStatus: string,
  existingLastVersion: string,
  incomingVersion: string
): IssueStatus {
  const normalized = normalizeIssueStatus(existingStatus) ?? "open";
  if (normalized === "ignored") return "ignored";
  if (normalized === "resolved") {
    return compareAppVersion(incomingVersion, existingLastVersion) > 0 ? "open" : "resolved";
  }
  return "open";
}

/**
 * 样本保存策略：每个问题最多保留 5 条。
 * 未满 5 条时直接保存；满 5 条后仅当新样本带来了现有样本尚未覆盖的应用版本或手机厂商时才补采一条（随后淘汰最旧一条）。
 */
export function shouldStoreSample(
  existingSamples: SampleMetadata[],
  incoming: { appVersion: string; manufacturer: string },
  maxSamples = MAX_SAMPLES_PER_ISSUE
): boolean {
  if (existingSamples.length < maxSamples) return true;
  const hasVersion = existingSamples.some((s) => s.app_version === incoming.appVersion);
  const hasManufacturer = existingSamples.some(
    (s) => s.manufacturer.toLowerCase() === incoming.manufacturer.toLowerCase()
  );
  return !hasVersion || !hasManufacturer;
}

function topBuckets(counts: Map<string, number>): DistributionBucket[] {
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([label, count]) => ({ label, count }));
}

/** 根据样本集合汇总版本、厂商和 Android SDK 分布，便于详情弹窗直观展示。 */
export function summarizeSampleDistribution(samples: SampleMetadata[]): SampleDistribution {
  const versions = new Map<string, number>();
  const manufacturers = new Map<string, number>();
  const sdks = new Map<string, number>();
  for (const s of samples) {
    versions.set(s.app_version, (versions.get(s.app_version) ?? 0) + 1);
    manufacturers.set(s.manufacturer, (manufacturers.get(s.manufacturer) ?? 0) + 1);
    if (typeof s.sdk_int === "number" && s.sdk_int > 0) {
      const sdkLabel = `API ${s.sdk_int}`;
      sdks.set(sdkLabel, (sdks.get(sdkLabel) ?? 0) + 1);
    }
  }
  return {
    versions: topBuckets(versions),
    manufacturers: topBuckets(manufacturers),
    sdks: topBuckets(sdks),
  };
}
