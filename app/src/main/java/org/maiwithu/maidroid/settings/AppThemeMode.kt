package org.maiwithu.maidroid.settings

/** 外观主题策略：默认跟随系统深浅色切换，也可在设置中手动锁定为浅色或深色。 */
internal enum class AppThemeMode(val storageValue: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    companion object {
        fun fromStorage(raw: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == raw } ?: System
    }
}

/** 结合用户偏好与系统当前深色状态解析本帧是否启用深色主题。 */
internal fun resolveUseDarkTheme(mode: AppThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (mode) {
        AppThemeMode.System -> systemInDarkTheme
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
