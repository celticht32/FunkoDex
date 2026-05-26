package com.funkodex.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.funkodex.ui.screens.collection.CollectionScreen
import com.funkodex.ui.screens.detail.DetailScreen
import com.funkodex.ui.screens.prescan.PreScanScreen
import com.funkodex.ui.screens.reports.ReportsScreen
import com.funkodex.ui.screens.scanner.ScannerScreen
import com.funkodex.ui.screens.settings.CategoryFilterScreen
import com.funkodex.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Collection    : Screen("collection",       "Collection", Icons.Default.Inventory2)
    object Scanner       : Screen("scanner",          "Scan",       Icons.Default.QrCodeScanner)
    object PreScan       : Screen("prescan",          "Check",      Icons.Default.ShoppingCart)
    object Reports       : Screen("reports",          "Reports",    Icons.Default.BarChart)
    object Settings      : Screen("settings",         "Settings",   Icons.Default.Settings)
    // Non-tab screens
    object Detail        : Screen("detail/{itemId}",  "",           Icons.Default.Info) {
        fun routeFor(id: String) = "detail/$id"
    }
    object CategoryFilter: Screen("category_filter",  "",           Icons.Default.FilterList)
}

private val bottomNavItems = listOf(
    Screen.Collection, Screen.Scanner, Screen.PreScan, Screen.Reports, Screen.Settings
)

@Composable
fun FunkoDexNavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val entry by navController.currentBackStackEntryAsState()
                val current = entry?.destination
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon     = { Icon(screen.icon, screen.label) },
                        label    = { Text(screen.label) },
                        selected = current?.hierarchy?.any { it.route == screen.route } == true,
                        onClick  = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Collection.route,
            modifier         = Modifier.padding(padding)
        ) {
            composable(Screen.Collection.route) {
                CollectionScreen(
                    onItemClick = { id -> navController.navigate(Screen.Detail.routeFor(id)) }
                )
            }
            composable(Screen.Scanner.route) { ScannerScreen() }
            composable(Screen.PreScan.route)  { PreScanScreen() }
            composable(Screen.Reports.route)  {
                ReportsScreen(
                    onItemClick = { id -> navController.navigate(Screen.Detail.routeFor(id)) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToCategoryFilter = {
                        navController.navigate(Screen.CategoryFilter.route)
                    }
                )
            }
            composable(Screen.CategoryFilter.route) {
                CategoryFilterScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route     = Screen.Detail.route,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
            ) {
                DetailScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
