/**
 * 应用主屏幕外壳，负责承载导航路由与底部浮动 Tab Bar。
 */
package com.lumasr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * 底部主导航项。保留路由值，避免影响现有 Navigation Compose 状态。
 */
sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    data object Process : BottomNavItem("process", "处理", Icons.AutoMirrored.Rounded.FormatListBulleted)
    data object Settings : BottomNavItem("settings", "设置", Icons.Rounded.Settings)
}

@Composable
fun MainScreen(viewModel: LumaViewModel) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsState()
    val showBottomBar = state.screen == LumaScreen.EDITING

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = state.screen == LumaScreen.COMPARE,
                transitionSpec = {
                    if (targetState) {
                        (slideInVertically(animationSpec = tween(280)) { it / 8 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(animationSpec = tween(220)) { -it / 10 } + fadeOut(tween(150)))
                    } else {
                        (slideInVertically(animationSpec = tween(320)) { -it / 10 } + fadeIn(tween(240))) togetherWith
                            (slideOutVertically(animationSpec = tween(240)) { it / 4 } + fadeOut(tween(160)))
                    }
                },
                label = "mainModeTransition",
                modifier = Modifier.fillMaxSize()
            ) { showCompare ->
                if (showCompare) {
                    CompareScreenV2(
                        state = state,
                        onSave = viewModel::saveResultToGallery,
                        onBackHome = viewModel::backHome
                    )
                } else {
                    LumaNavHost(
                        navController = navController,
                        viewModel = viewModel,
                        state = state,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LumaNavigationBar(
                    navController = navController
                )
            }
        }
    }
}

@Composable
private fun LumaNavHost(
    navController: NavHostController,
    viewModel: LumaViewModel,
    state: LumaUiState,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        modifier = modifier,
        startDestination = BottomNavItem.Process.route,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable(BottomNavItem.Process.route) {
            ProcessTabV2(
                state = state,
                onPickImages = viewModel::onImagesSelected,
                onModelSelected = viewModel::selectModel,
                onScaleChanged = viewModel::setScale,
                onNoiseChanged = viewModel::setNoise,
                onAccelerationChanged = viewModel::setAccelerationMode,
                onTtaChanged = viewModel::setTta,
                onStart = viewModel::startProcessing,
                onClearImage = viewModel::clearSelectedImage,
                onCancel = viewModel::cancelProcessing
            )
        }
        composable(BottomNavItem.Settings.route) {
            SettingsScreenV2(
                state = state,
                onAccelerationChanged = viewModel::setAccelerationMode
            )
        }
    }
}

@Composable
private fun LumaNavigationBar(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val items = listOf(BottomNavItem.Process, BottomNavItem.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val selectedRoute = items.firstOrNull { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }?.route ?: BottomNavItem.Process.route

    LumaFloatingTabBar(
        items = items,
        selectedRoute = selectedRoute,
        onTabClick = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        modifier = modifier
    )
}

@Composable
private fun LumaFloatingTabBar(
    items: List<BottomNavItem>,
    selectedRoute: String,
    onTabClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isSystemInDarkTheme()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val barColor = colorScheme.surfaceVariant.copy(alpha = if (darkTheme) 0.82f else 0.88f)
    val selectedPillColor = if (darkTheme) {
        colorScheme.surface.copy(alpha = 0.98f)
    } else {
        Color.White.copy(alpha = 0.98f)
    }
    val borderColor = colorScheme.outline.copy(alpha = if (darkTheme) 0.30f else 0.22f)
    val selectedPillBorderColor = if (darkTheme) {
        colorScheme.outline.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.82f)
    }
    val selectedPillShadowAlpha = if (darkTheme) 0.36f else 0.18f
    val selectedColor = colorScheme.onSurface
    val unselectedColor = colorScheme.onSurfaceVariant.copy(alpha = 0.58f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 58.dp, top = 8.dp, end = 58.dp, bottom = 24.dp)
            .height(76.dp)
    ) {
        val tabWidth = maxWidth / items.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "tabIndicatorOffset"
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(40.dp),
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.10f)
                )
                .border(1.dp, borderColor, RoundedCornerShape(40.dp)),
            shape = RoundedCornerShape(40.dp),
            color = barColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .width(tabWidth - 16.dp)
                        .height(64.dp)
                        .shadow(
                            elevation = 11.dp,
                            shape = RoundedCornerShape(34.dp),
                            ambientColor = Color.Black.copy(alpha = selectedPillShadowAlpha),
                            spotColor = Color.Black.copy(alpha = selectedPillShadowAlpha)
                        )
                        .clip(RoundedCornerShape(34.dp))
                        .background(selectedPillColor)
                        .border(
                            width = 1.dp,
                            color = selectedPillBorderColor,
                            shape = RoundedCornerShape(34.dp)
                        )
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        FloatingTabItem(
                            item = item,
                            selected = item.route == selectedRoute,
                            selectedColor = selectedColor,
                            unselectedColor = unselectedColor,
                            onClick = { onTabClick(item.route) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingTabItem(
    item: BottomNavItem,
    selected: Boolean,
    selectedColor: Color,
    unselectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        animationSpec = tween(180),
        label = "tabContentColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tabIconScale"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = contentColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.title,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}
