package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 全应用统一的横向推移转场：forward 为 true 时新内容从右边推进来、旧内容往左推出，false 时反过来。
 * 启动页、OOBE 步骤、主页标签、设置详情、平台分类都用它，只按场景微调阻尼比（越小越“弹”）。
 * 尺寸变化时裁掉越界部分，避免推移过程中内容画到容器外面。
 */
internal fun horizontalSlideTransition(forward: Boolean, dampingRatio: Float): ContentTransform {
    val direction = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = spring(dampingRatio = dampingRatio, stiffness = Spring.StiffnessMediumLow)
        ) { fullWidth -> fullWidth * direction },
        initialContentExit = slideOutHorizontally(
            animationSpec = spring(dampingRatio = dampingRatio, stiffness = Spring.StiffnessMediumLow)
        ) { fullWidth -> -fullWidth * direction },
        sizeTransform = SizeTransform(clip = true)
    )
}
