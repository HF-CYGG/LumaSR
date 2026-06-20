/**
 * 本文件定义了应用的主页 (Home Screen)。
 * 包含了大标题 (LargeTopAppBar)、核心操作卡片 (选择素材)、快捷功能栏以及最近任务列表。
 */
package com.lumasr.ui

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumasr.domain.AccelerationMode

/**
 * 主页屏幕，包含大标题、核心操作卡片、快捷功能和最近任务。
 * 负责展示应用的首页内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: LumaUiState,
    onPickImage: (Uri) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val photoPicker = rememberImagePicker(onPickImage)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "LumaSR",
                        fontWeight = FontWeight.Bold
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 核心操作大卡片 (SubTask 2.2)
            item {
                CoreActionCard(
                    onPickImage = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
            }

            // 快捷功能横向滑动 (SubTask 2.3)
            item {
                Text(
                    text = "常用功能",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                QuickFunctionsRow()
            }

            // 状态信息卡片
            item {
                StatusCard(
                    icon = Icons.Rounded.Bolt,
                    title = if (state.accelerationMode == AccelerationMode.CPU) "CPU 模式" else "GPU 可用",
                    subtitle = "离线模式 · 模型已就绪",
                    value = if (state.models.isEmpty()) "加载中" else "${state.models.size} 个模型",
                    success = state.models.isNotEmpty()
                )
            }

            // 底部最近任务列表 (SubTask 2.3)
            item {
                Text(
                    text = "最近处理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (state.recentTasks.isEmpty()) {
                item {
                    RecentTaskPlaceholder()
                }
            } else {
                items(state.recentTasks.take(3)) { task ->
                    RecentTaskCard(task)
                }
            }
        }
    }
}

/**
 * 核心操作大卡片，用于开始增强
 */
@Composable
fun CoreActionCard(onPickImage: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = CardDefaults.outlinedCardBorder(),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconTile(icon = Icons.Rounded.AutoFixHigh, tint = MaterialTheme.colorScheme.primary)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "开始增强",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "选择照片，使用设备本地模型处理。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPickImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择素材", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private data class ActionChipData(val icon: ImageVector, val text: String)

/**
 * 横向滑动的快捷功能列
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFunctionsRow() {
    val items = listOf(
        ActionChipData(Icons.Rounded.PhotoLibrary, "照片超分"),
        ActionChipData(Icons.Rounded.AutoFixHigh, "动漫线稿"),
        ActionChipData(Icons.AutoMirrored.Rounded.FormatListBulleted, "批量队列")
    )
    
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { item ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = item.text, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
