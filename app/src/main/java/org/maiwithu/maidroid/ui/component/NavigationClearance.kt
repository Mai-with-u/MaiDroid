package org.maiwithu.maidroid.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 竖屏主页的悬浮底栏连同底部留白大约 80dp，列表末尾再多留一点，最后一项能完整滚到底栏上方。 */
private val BottomNavigationClearance = 112.dp

/**
 * 主页各页面滚动列表末尾要留的空白：竖屏时底栏悬浮在页面上，要留出它的高度；
 * 横屏时导航在左侧另占一列，主页外壳会把这里换成很小的留白。默认值对应竖屏，单独预览页面时也不用额外提供。
 */
internal val LocalBottomNavigationClearance = compositionLocalOf { BottomNavigationClearance }
