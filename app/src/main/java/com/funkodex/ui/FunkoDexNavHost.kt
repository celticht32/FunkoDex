package com.funkodex.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.funkodex.ui.screens.SplashScreen
import com.funkodex.ui.screens.collection.CollectionScreen
import com.funkodex.ui.screens.detail.DetailScreen
import com.funkodex.ui.screens.prescan.PreScanScreen
import com.funkodex.ui.screens.reports.ReportsScreen
import com.funkodex.ui.screens.scanner.ScannerScreen
import com.funkodex.ui.screens.settings.CategoryFilterScreen
import com.funkodex.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Splash        : Screen("splash",           "",           Icons.Default.Info)
    object Collection    : Screen("collection",       "My Dex", Icons.Default.Inventory2)
    object Scanner       : Screen("scanner",          "Add",        Icons.Default.QrCodeScanner)
    object PreScan       : Screen("prescan",          "Check",      Icons.Default.ShoppingCart)
    object Reports       : Screen("reports",          "Reports",    Icons.Default.BarChart)
    object Settings      : Screen("settings",         "Settings",   Icons.Default.Settings)
    object Detail        : Screen("detail/{itemId}",  "",           Icons.Default.Info) {
        fun routeFor(id: String) = "detail/$id"
    }
    object CategoryFilter: Screen("category_filter",  "",           Icons.Default.FilterList)
}

private val bottomNavItems = listOf(
    Screen.Collection, Screen.Scanner, Screen.PreScan, Screen.Reports, Screen.Settings
)

@Composable
fun FunkoDexNavHost(
    deepLinkItemId: String? = null,
    openScannerOnStart: Boolean = false,
) {
    val navController = rememberNavController()

    LaunchedEffect(deepLinkItemId) {
        if (!deepLinkItemId.isNullOrEmpty()) {
            navController.navigate(Screen.Detail.routeFor(deepLinkItemId)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(openScannerOnStart) {
        if (openScannerOnStart) {
            navController.navigate(Screen.Scanner.route) {
                launchSingleTop = true
                popUpTo(Screen.Collection.route)
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val onSplash = currentRoute == Screen.Splash.route

    Scaffold(
        bottomBar = {
            if (!onSplash) {
                NavigationBar {
                    val entry by navController.currentBackStackEntryAsState()
                    val current = entry?.destination
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon     = { Icon(screen.icon, screen.label) },
                            label    = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                            selected = current?.hierarchy?.any { it.route == screen.route } == true,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Splash.route,
            modifier         = Modifier.padding(if (onSplash) PaddingValues(0.dp) else scaffoldPadding)
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(onSplashComplete = {
                    navController.navigate(Screen.Collection.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                })
            }
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
