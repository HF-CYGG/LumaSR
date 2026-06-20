/**
 * 本文件负责展示历史记录与任务队列页面。
 * 包含处理中与已完成的任务切换，采用极简风格列表进行展示，
 * 方便用户查看历史处理结果。
 */
package com.lumasr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(state: LumaUiState) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("处理中", "已完成")

    val processingTasks = state.recentTasks.filter { it.status == RenderTaskStatus.RUNNING }
    val completedTasks = state.recentTasks.filter { it.status != RenderTaskStatus.RUNNING }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("历史任务") }
                )
                PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            val currentTasks = if (selectedTabIndex == 0) processingTasks else completedTasks

            if (currentTasks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "暂无任务",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(currentTasks) { task ->
                    HistoryTaskItem(task = task)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun HistoryTaskItem(task: RenderTaskSummary) {
    val icon = when (task.status) {
        RenderTaskStatus.DONE -> Icons.Rounded.CheckCircle
        RenderTaskStatus.FAILED -> Icons.Rounded.ErrorOutline
        RenderTaskStatus.CANCELLED -> Icons.Rounded.Close
        RenderTaskStatus.RUNNING -> Icons.Rounded.Bolt
    }

    val iconTint = when (task.status) {
        RenderTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        RenderTaskStatus.DONE -> MaterialTheme.colorScheme.primary
        RenderTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        RenderTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when (task.status) {
        RenderTaskStatus.DONE -> "已完成"
        RenderTaskStatus.FAILED -> "失败"
        RenderTaskStatus.CANCELLED -> "已取消"
        RenderTaskStatus.RUNNING -> "处理中"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${task.modelName} · ${task.scale}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (task.status == RenderTaskStatus.RUNNING) "${(task.progress * 100).toInt()}%" else statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = iconTint,
                    fontWeight = FontWeight.Bold
                )
            }

            if (task.status == RenderTaskStatus.RUNNING) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
