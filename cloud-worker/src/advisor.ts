// 自动诊断顾问：读取客户端上传的诊断快照元数据，提炼环境隐患并拼装可直接发给用户的分步解决话术。

export interface SnapshotInput {
  appVersion?: string;
  device?: string;
  sdkInt?: number;
  availableStorageMb?: number | null;
  availableMemMb?: number | null;
  permissions?: {
    storage?: boolean;
    notifications?: boolean;
    batteryIgnored?: boolean;
  };
  errorCode?: string | null;
  failureMessage?: string | null;
  lastExitReason?: string | null;
}

export interface DiagnosticFinding {
  level: "critical" | "warn" | "info";
  title: string;
  step: string;
}

const CODE_STEPS: Record<string, { title: string; step: string }> = {
  "NET-OFFLINE": {
    title: "手机当前未连接网络",
    step: "请先打开手机 Wi-Fi 或移动数据，确认浏览器能正常上网后回到 MaiDroid 点击重试。",
  },
  "NET-DNS": {
    title: "当前网络域名解析失败",
    step: "请在手机设置中关闭“私人 DNS”或正在开启的代理工具，或切换一下 Wi-Fi / 移动数据后重试。",
  },
  "NET-TIMEOUT": {
    title: "下载连接超时",
    step: "下载线路连接较慢，请关闭 VPN/代理或切换网络后点击重试（MaiDroid 会自动切换其他镜像）。",
  },
  "NET-TLS": {
    title: "安全证书校验失败",
    step: "请检查手机系统时间是否开启“自动设置时间”，若连着需要网页认证的校园网/商场 Wi-Fi 请先完成登录或改用流量。",
  },
  "TIME-SKEW": {
    title: "手机系统时间偏差过大",
    step: "请进入手机系统的“日期和时间”设置，打开“自动设置时间”后再回到 MaiDroid 重试。",
  },
  "DISK-FULL": {
    title: "手机剩余存储空间不足",
    step: "请先进入 MaiDroid“设置 > 清理空间”清理下载缓存，并在手机系统里清理出至少 2 GB 空余空间后重试。",
  },
  "MEM-OOM": {
    title: "手机可用运行内存不足",
    step: "后台内存吃紧导致进程被回收，请先划掉其他占内存的大型应用或重启一次手机后再打开 MaiDroid。",
  },
  "PORT-BUSY": {
    title: "本地运行端口被其他程序占用",
    step: "8001 端口正被其他应用（如 Termux 里的 MaiBot）占用，请先关闭该应用或在“设置 > 安全退出”后重新打开。",
  },
  "CFG-TOML": {
    title: "MaiBot 配置文件语法有误",
    step: "最近修改过的配置文件存在引号、括号或等号缺失，请在“设置 > MaiBot 文件”中打开对应 toml 检查修正。",
  },
  "DB-DOWNGRADE": {
    title: "数据库版本高于要切换的代码分支",
    step: "MaiBot 数据库不支持降级，请保持在更新的版本通道，或在“设置 > MaiBot 更新”升级到最新提交。",
  },
  "PROOT-182": {
    title: "当前系统或兼容容器拦截了 proot 加载器",
    step: "退出码 182 表示容器加载器无法完成内存映射，目前仅支持原生安卓设备，纯血鸿蒙“卓易通”等兼容容器暂无法运行。",
  },
  "MB-OLD": {
    title: "MaiBot 版本低于当前 QQ 适配器要求",
    step: "请先进入 MaiDroid“设置 > MaiBot 更新”升级到最新版 MaiBot，再回来安装或配置 QQ 适配器。",
  },
};

export function buildDiagnosticAdvice(code: string, snap: SnapshotInput): {
  findings: DiagnosticFinding[];
  replyText: string;
} {
  const findings: DiagnosticFinding[] = [];

  if (snap.permissions?.storage === false) {
    findings.push({
      level: "critical",
      title: "未授予必选的存储权限",
      step: "打开 MaiDroid“设置 > 权限管理”（或首次引导第一步），开启“所有文件访问”权限。",
    });
  }

  if (typeof snap.availableStorageMb === "number" && snap.availableStorageMb < 512) {
    findings.push({
      level: "critical",
      title: `剩余存储仅 ${snap.availableStorageMb} MB`,
      step: "前往“设置 > 清理空间”清理缓存，并从手机相册/应用中腾出至少 2 GB 可用空间。",
    });
  }

  const causeCode = snap.errorCode?.includes("/") ? snap.errorCode.split("/")[1] : snap.errorCode;
  if (causeCode && CODE_STEPS[causeCode]) {
    findings.push({
      level: "critical",
      title: `${snap.errorCode}：${CODE_STEPS[causeCode].title}`,
      step: CODE_STEPS[causeCode].step,
    });
  } else if (snap.errorCode) {
    findings.push({
      level: "warn",
      title: `记录到错误码 ${snap.errorCode}`,
      step: snap.failureMessage
        ? `报错摘要：“${snap.failureMessage}”，请结合下方终端尾部日志排查具体失败命令。`
        : "请结合下方终端尾部日志查看最后一步报错输出。",
    });
  }

  const exitText = snap.lastExitReason ?? "";
  const killedInBg =
    exitText.includes("其他") ||
    exitText.includes("内存不足") ||
    exitText.includes("信号") ||
    exitText.toLowerCase().includes("phantom");

  if (snap.permissions?.batteryIgnored === false || killedInBg) {
    findings.push({
      level: killedInBg ? "critical" : "warn",
      title: killedInBg ? `存在后台被系统清理记录（${exitText}）` : "尚未开启忽略电池优化",
      step: "进入 MaiDroid“设置 > 权限管理”，把“忽略电池优化”“自启动和后台活动”打开，并在系统最近任务界面给 MaiDroid 加锁。",
    });
  }

  if (snap.permissions?.notifications === false) {
    findings.push({
      level: "info",
      title: "通知权限未开启",
      step: "建议在“设置 > 权限管理”开启通知权限，便于观察前台常驻运行状态与接收异常提醒。",
    });
  }

  const actionable = findings.filter((f) => f.level !== "info");
  const chosen = actionable.length > 0 ? actionable : findings;
  const lines =
    chosen.length > 0
      ? chosen.map((f, idx) => `${idx + 1}. ${f.step}`)
      : ["1. 环境权限与存储状态正常，请升级到最新 MaiDroid 版本并在“设置 > MaiBot 更新”同步最新代码后重试。"];

  const header = `已查看你的求助码 #${code}（MaiDroid ${snap.appVersion || "未知"} · ${snap.device || "未知设备"}）：`;
  return {
    findings,
    replyText: `${header}\n${lines.join("\n")}`,
  };
}
