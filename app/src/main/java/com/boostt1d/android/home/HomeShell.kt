package com.boostt1d.android.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
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
    DOCTOR_VISIT,
    BLOOD_GLUCOSE,
    EVENT_LOG,
    BOLUS_CALCULATOR,
    DATA_SOURCE,
    THERAPY_PROFILE,
    SETTINGS,
    HOW_IT_WORKS,
    HELP,
    SUPPORT,
    ABOUT,
}

/**
 * Persistent bottom bar.
 *
 * Five tabs. Dashboard and Snap go straight to a screen; Insights, Logs and Menu open a
 * submenu above the bar.
 *
 * Snap sits in the middle and is drawn as a filled button rather than a tab, because it is
 * the one thing here that is an action rather than a place. Photographing a meal is the most
 * frequent thing anyone does in this app and it used to take two taps behind a Food menu.
 */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard),
    INSIGHTS("Insights", Icons.Filled.Insights),
    SNAP("Snap", Icons.Filled.CameraAlt),
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
    /** The coaching tip to show above the bar, or null. The caller owns the rules. */
    tip: BoostTipId? = null,
    onDismissTip: (BoostTipId) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val colors = BoostTheme.colors
    var openTab by remember { mutableStateOf<HomeTab?>(null) }

    val insightsItems = listOf(
        SubmenuItem("What Happened?", "Your last seven days, in plain language", Icons.Filled.Insights, HomeDestination.INSIGHTS),
        SubmenuItem("Doctor Visit", "A report to bring to your appointment", Icons.Filled.MedicalServices, HomeDestination.DOCTOR_VISIT),
    )
    val logsItems = listOf(
        SubmenuItem("BG Log", "Readings and charts", Icons.Filled.ShowChart, HomeDestination.BLOOD_GLUCOSE),
        SubmenuItem("Event Log", "Insulin, carbs and events", Icons.AutoMirrored.Filled.MenuBook, HomeDestination.EVENT_LOG),
        SubmenuItem("Food Log", "Meals, carbs and photos", Icons.Filled.Restaurant, HomeDestination.FOOD_LOG),
    )
    // Eight destinations do not fit one column above the bar, so Menu lays them out two
    // across, as iOS does. Every item is visible on the first tap.
    val menuItems = listOf(
        SubmenuItem("Data Source", "Nightscout or manual", Icons.Filled.Cloud, HomeDestination.DATA_SOURCE),
        SubmenuItem("Insulin Doses", "Basal, I:C and ISF", Icons.Filled.Tune, HomeDestination.THERAPY_PROFILE),
        SubmenuItem("Calculator", "How a bolus works", Icons.Filled.Calculate, HomeDestination.BOLUS_CALCULATOR),
        SubmenuItem("Profile", "Units and account", Icons.Filled.Person, HomeDestination.SETTINGS),
        SubmenuItem("How It Works", "Product tutorial", Icons.AutoMirrored.Filled.MenuBook, HomeDestination.HOW_IT_WORKS),
        SubmenuItem("Help", "Setup guides", Icons.AutoMirrored.Filled.HelpOutline, HomeDestination.HELP),
        SubmenuItem("Support Us", "Optional donation", Icons.Filled.Favorite, HomeDestination.SUPPORT),
        SubmenuItem("About", "Version and legal", Icons.Filled.Info, HomeDestination.ABOUT),
    )

    val activeTab = when (destination) {
        HomeDestination.DASHBOARD -> HomeTab.DASHBOARD
        HomeDestination.SNAP_MEAL -> HomeTab.SNAP
        HomeDestination.INSIGHTS, HomeDestination.DOCTOR_VISIT -> HomeTab.INSIGHTS
        HomeDestination.BLOOD_GLUCOSE, HomeDestination.EVENT_LOG, HomeDestination.FOOD_LOG -> HomeTab.LOGS
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
                visible = openTab == HomeTab.INSIGHTS,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Submenu(insightsItems, destination) { onSelect(it); openTab = null }
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
                Submenu(menuItems, destination, columns = 2) { onSelect(it); openTab = null }
            }

            // A tip sits directly above the bar it is talking about. It never covers the bar
            // itself, so the thing it points at stays tappable while it is up.
            if (tip != null && openTab == null) {
                TipBubble(tip, onDismiss = { onDismissTip(tip) })
            }

            BottomBar(
                active = activeTab,
                openTab = openTab,
                onTap = { tab ->
                    when (tab) {
                        // The two that are a destination rather than a menu.
                        HomeTab.DASHBOARD -> {
                            openTab = null
                            onSelect(HomeDestination.DASHBOARD)
                        }
                        HomeTab.SNAP -> {
                            openTab = null
                            onSelect(HomeDestination.SNAP_MEAL)
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
    columns: Int = 1,
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
        if (columns > 1) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { item ->
                        SubmenuRow(item, item.destination == current, Modifier.weight(1f)) { onSelect(item.destination) }
                    }
                    // An odd last row keeps its column width rather than stretching one tile
                    // across the whole sheet.
                    repeat(columns - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        } else {
            items.forEach { item ->
                SubmenuRow(item, item.destination == current, Modifier.fillMaxWidth()) { onSelect(item.destination) }
            }
        }
    }
}

@Composable
private fun SubmenuRow(
    item: SubmenuItem,
    isCurrent: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = modifier
            .background(
                if (isCurrent) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(BoostRadius.lg),
            )
            .clickable(onClick = onSelect)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.detail,
                fontSize = 13.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TipBubble(tip: BoostTipId, onDismiss: () -> Unit) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoostSpacing.sm)
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(BoostRadius.lg))
            .padding(BoostSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
    ) {
        Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tip.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(tip.message, fontSize = 13.sp, color = colors.textSecondary)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss tip", tint = colors.textTertiary, modifier = Modifier.size(18.dp))
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
                // Snap is an action, so it is a filled button and keeps its weight whether
                // or not it is the current screen. Tinting it like the others would have it
                // disappear into the row it is supposed to lead.
                val isAction = tab == HomeTab.SNAP
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = highlighted,
                            role = if (isAction) Role.Button else Role.Tab,
                            onClick = { onTap(tab) },
                        )
                        .semantics(mergeDescendants = true) { contentDescription = tab.label }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (isAction) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(colors.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = if (highlighted) colors.primary else colors.textTertiary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (highlighted || isAction) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (highlighted || isAction) colors.primary else colors.textTertiary,
                    )
                }
            }
        }
    }
}
