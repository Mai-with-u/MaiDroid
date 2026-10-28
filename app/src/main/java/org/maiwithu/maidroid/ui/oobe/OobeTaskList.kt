package org.maiwithu.maidroid.ui.oobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.oobe.OobeTask
import org.maiwithu.maidroid.oobe.OobeTaskStatus

// “配置容器”“安装 MaiBot”两步的任务卡片列表。进度变化时自动滚到正在做（或第一张没完成）的卡片。

/** 手机竖屏：一列卡片。 */
@Composable
internal fun CompactOobeTaskList(tasks: List<OobeTask>) {
    val listState = rememberLazyListState()
    val focusIndex = rememberFocusedTaskIndex(tasks)

    LaunchedEffect(focusIndex) {
        focusIndex?.let { listState.animateScrollToItem(it) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(tasks) { task -> OobeTaskCard(task) }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 平板和横屏：每张卡片至少 300dp 宽，按可用宽度自动排成多列。 */
@Composable
internal fun AdaptiveOobeTaskList(tasks: List<OobeTask>) {
    val gridState = rememberLazyGridState()
    val focusIndex = rememberFocusedTaskIndex(tasks)

    LaunchedEffect(focusIndex) {
        focusIndex?.let { gridState.animateScrollToItem(it) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(tasks) { task -> OobeTaskCard(task) }
    }
}

// 优先聚焦正在进行的任务，其次第一张没完成的，全部完成时聚焦最后一张。
@Composable
private fun rememberFocusedTaskIndex(tasks: List<OobeTask>): Int? =
    remember(tasks) {
        tasks.indexOfFirst { it.status == OobeTaskStatus.Running }.takeIf { it >= 0 }
            ?: tasks.indexOfFirst { it.status != OobeTaskStatus.Done }.takeIf { it >= 0 }
            ?: tasks.lastIndex.takeIf { it >= 0 }
    }
