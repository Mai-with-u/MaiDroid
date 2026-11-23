package org.maiwithu.maidroid.runtime

/** 一个 PyPI / uv 软件源：显示名称与 simple 索引地址（空串代表交由 MaiBot 自带 pyproject 配置决定）。 */
internal data class PyPiMirror(val name: String, val indexUrl: String) {
    /** 为空串时表示不覆盖 UV_INDEX，沿用 MaiBot 仓库自带的索引顺序。 */
    val isBuiltInDefault: Boolean get() = indexUrl.isEmpty()

    /** 宿主侧并发测速实际访问的索引探测地址。 */
    val probeUrl: String get() = if (isBuiltInDefault) PyPiMirrors.BUILT_IN_PROBE_URL else indexUrl
}

/**
 * 容器里 uv sync / uv run 使用的 PyPI 镜像源注册表。
 * 统一从 MaiBotScripts 抽离以便设置测速、用户手动指定/输入自定义索引及在容器 /root/.maidroid/pypi-index 间双向同步。
 */
internal object PyPiMirrors {
    /** 使用 MaiBot 项目自带源配置时展示给用户的预设名称。 */
    const val BUILT_IN_NAME = "MaiBot 自带的源"

    /** MaiBot pyproject 默认主源（阿里云 PyPI），供无覆盖项测速时探测实际可达性与延迟。 */
    const val BUILT_IN_PROBE_URL = "https://mirrors.aliyun.com/pypi/simple"

    /** 当前推荐与兜底的全部内置 PyPI 镜像源（顺序兼作未测速前的默认权重）。 */
    val ALL: List<PyPiMirror> = listOf(
        PyPiMirror("清华 PyPI 镜像", "https://pypi.tuna.tsinghua.edu.cn/simple"),
        PyPiMirror("阿里云 PyPI 镜像", "https://mirrors.aliyun.com/pypi/simple"),
        PyPiMirror("腾讯云 PyPI 镜像", "https://mirrors.cloud.tencent.com/pypi/simple"),
        PyPiMirror("中科大 PyPI 镜像", "https://mirrors.ustc.edu.cn/pypi/simple"),
        PyPiMirror("PyPI 官方源", "https://pypi.org/simple"),
        PyPiMirror(BUILT_IN_NAME, "")
    )

    /** 供旧逻辑或未知异常文案列举的源名称串。 */
    val allNames: String get() = ALL.joinToString("、") { it.name }
}
