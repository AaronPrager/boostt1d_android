package com.boostt1d.android.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Root destinations. Selecting one replaces the screen — it is not pushed on top of a
 * menu, so Back never lands the user in a menu they did not open.
 */
enum class HomeDestination {
    DASHBOARD,
    FOOD_LOG,
    SNAP_MEAL,
    INSIGHTS,
    BLOOD_GLUCOSE,
    EVENT_LOG,
    BOLUS_CALCULATOR,
    DATA_SOURCE,
    THERAPY_PROFILE,
    SETTINGS,
    ABOUT,
}

/**
 * Persistent bottom bar.
 *
 * iOS carries five tabs — Dashboard, Food, Insights, Logs, Menu. Food opens a submenu with
 * Snap a Meal and the Food Log, as on iOS.
 *
 * Insights opens the What Happened report directly. On iOS it is a submenu holding the report
 * and the Doctor Visit report; the submenu returns with the second item.
 */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard),
    FOOD("Food", Icons.Filled.Restaurant),
    INSIGHTS("Insights", Icons.Filled.Insights),
    LOGS("Logs", Icons.Filled.ListAlt),
    MENU("Menu", Icons.Filled.Menu),
}

private data class SubmenuItem(
    val label: String,
    val detail: String,
    val icon: ImageVector,
    val destination: HomeDestination,
)

@Composable
fun HomeShell(
    destination: HomeDestination,
    onSelect: (HomeDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val colors = BoostTheme.colors
    var openTab by remember { mutableStateOf<HomeTab?>(null) }

    val foodItems = listOf(
        SubmenuItem("Snap a Meal", "Photo to carb estimate", Icons.Filled.CameraAlt, HomeDestination.SNAP_MEAL),
        SubmenuItem("Food Log", "Meals, carbs and photos", Icons.Filled.Restaurant, HomeDestination.FOOD_LOG),
    )
    val logsItems = listOf(
        SubmenuItem("BG Log", "Readings and charts", Icons.Filled.ShowChart, HomeDestination.BLOOD_GLUCOSE),
        SubmenuItem("Event Log", "Insulin, carbs and events", Icons.AutoMirrored.Filled.MenuBook, HomeDestination.EVENT_LOG),
    )
    val menuItems = listOf(
        SubmenuItem("Data Source", "Nightscout or manual entry", Icons.Filled.Cloud, HomeDestination.DATA_SOURCE),
        SubmenuItem("Insulin Doses", "Basal, carb ratio, correction", Icons.Filled.Tune, HomeDestination.THERAPY_PROFILE),
        SubmenuItem("Insulin Calculator", "How a bolus is worked out", Icons.Filled.Calculate, HomeDestination.BOLUS_CALCULATOR),
        SubmenuItem("Profile", "Units, range and account", Icons.Filled.Person, HomeDestination.SETTINGS),
        SubmenuItem("About", "Version and legal", Icons.Filled.Info, HomeDestination.ABOUT),
    )

    val activeTab = when (destination) {
        HomeDestination.DASHBOARD -> HomeTab.DASHBOARD
        HomeDestination.FOOD_LOG, HomeDestination.SNAP_MEAL -> HomeTab.FOOD
        HomeDestination.INSIGHTS -> HomeTab.INSIGHTS
        HomeDestination.BLOOD_GLUCOSE, HomeDestination.EVENT_LOG -> HomeTab.LOGS
        else -> HomeTab.MENU
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        content(Modifier.fillMaxSize())

        // A submenu is transient, so it floats above the screen rather than insetting it —
        // otherwise every destination would resize itself each time one opened.
        if (openTab != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable { openTab = null },
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
        ) {
            AnimatedVisibility(
                visible = openTab == HomeTab.FOOD,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Submenu(foodItems, destination) { onSelect(it); openTab = null }
            }

            AnimatedVisibility(
                visible = openTab == HomeTab.LOGS,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Submenu(logsItems, destination) { onSelect(it); openTab = null }
            }

            AnimatedVisibility(
                visible = openTab == HomeTab.MENU,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Submenu(menuItems, destination) { onSelect(it); openTab = null }
            }

            BottomBar(
                active = activeTab,
                openTab = openTab,
                onTap = { tab ->
                    when (tab) {
                        HomeTab.DASHBOARD -> {
                            openTab = null
                            onSelect(HomeDestination.DASHBOARD)
                        }
                        HomeTab.INSIGHTS -> {
                            openTab = null
                            onSelect(HomeDestination.INSIGHTS)
                        }
                        // Tapping the open tab again closes it, so the bar is never a trap.
                        else -> openTab = if (openTab == tab) null else tab
                    }
                },
            )
        }
    }
}

@Composable
private fun Submenu(
    items: List<SubmenuItem>,
    current: HomeDestination,
    onSelect: (HomeDestination) -> Unit,
) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoostSpacing.sm)
            .background(colors.surface, RoundedCornerShape(BoostRadius.xl))
            .padding(BoostSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            val isCurrent = item.destination == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(BoostRadius.lg),
                    )
                    .clickable { onSelect(item.destination) }
                    .padding(horizontal = BoostSpacing.sm, vertical = BoostSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (isCurrent) colors.primary else colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(item.detail, fontSize = 13.sp, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    active: HomeTab,
    openTab: HomeTab?,
    onTap: (HomeTab) -> Unit,
) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(colors.border).size(1.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = BoostSpacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            HomeTab.entries.forEach { tab ->
                val highlighted = openTab == tab || (openTab == null && active == tab)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = highlighted,
                            role = Role.Tab,
                            onClick = { onTap(tab) },
                        )
                        .semantics(mergeDescendants = true) { contentDescription = tab.label }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (highlighted) colors.primary else colors.textTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (highlighted) colors.primary else colors.textTertiary,
                    )
                }
            }
        }
    }
}
