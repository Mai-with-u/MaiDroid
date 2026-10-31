package org.maiwithu.maidroid

/**
 * a.b.c 形式的版本号，后面可以带 `-rc1` 这类预发布后缀。两处在用：
 * MaiDroid 自更新比较 Release 标签（去掉开头的 v）和当前 versionName（appupdate），
 * 装 SnowLuma 适配器前比较 MaiBot 的 pyproject 版本和适配器要求的最低版本（platform/napcat）。
 * 比较规则与语义化版本一致：数字段逐位比较，缺的位按 0；数字相同时带后缀的更旧（0.2.0-rc1 < 0.2.0）。
 */
internal data class VersionNumber(
    val numbers: List<Int>,
    val preRelease: String? = null
) : Comparable<VersionNumber> {

    override fun compareTo(other: VersionNumber): Int {
        val length = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until length) {
            val diff = numbers.getOrElse(index) { 0 }.compareTo(other.numbers.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        numbers.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val CORE = Regex("""\d+(\.\d+)*""")
        private val PRE_RELEASE = Regex("""[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*""")

        /** 解析 "v0.1.3"、"0.2.0-rc1" 这类写法；构建元数据（+ 之后）忽略；格式不对返回 null。 */
        fun parse(text: String): VersionNumber? {
            val trimmed = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = trimmed.substringBefore('-')
            // 有连字符就必须跟着合法的后缀，"1.2.3-" 这种不算版本号
            val preRelease = if ('-' in trimmed) trimmed.substringAfter('-') else null
            if (!CORE.matches(core)) return null
            if (preRelease != null && !PRE_RELEASE.matches(preRelease)) return null
            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            return VersionNumber(numbers, preRelease)
        }

        // 没有后缀的是正式版，比任何同号的预发布都新；两边都有后缀时按点分段比较，纯数字段按数值比。
        private fun comparePreRelease(left: String?, right: String?): Int {
            if (left == right) return 0
            if (left == null) return 1
            if (right == null) return -1
            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val a = leftParts[index]
                val b = rightParts[index]
                val aNumber = a.toLongOrNull()
                val bNumber = b.toLongOrNull()
                val diff = when {
                    aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                    aNumber != null -> -1
                    bNumber != null -> 1
                    else -> a.compareTo(b)
                }
                if (diff != 0) return diff
            }
            return leftParts.size.compareTo(rightParts.size)
        }
    }
}
