package dev.muto.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.muto.app.R
import dev.muto.app.ui.screens.AppsScreen
import dev.muto.app.ui.screens.HomeScreen
import dev.muto.app.ui.screens.ListsScreen
import dev.muto.app.ui.screens.LogScreen
import dev.muto.app.ui.screens.RulesScreen
import dev.muto.app.ui.screens.SettingsScreen

private enum class Destination(val route: String, val label: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Filled.Shield),
    LISTS("lists", R.string.nav_lists, Icons.AutoMirrored.Filled.List),
    RULES("rules", R.string.nav_rules, Icons.Filled.Rule),
    LOG("log", R.string.nav_log, Icons.Filled.History),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val ROUTE_APPS = "settings/apps"

@Composable
fun MutoApp(
    viewModel: MutoViewModel,
    onRequestProtection: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val current = backStackEntry?.destination
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep a single copy of each tab and restore where the user was,
                                // so switching tabs never loses a scroll position or a search.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onRequestProtection = onRequestProtection,
                    onOpenLog = { navController.navigate(Destination.LOG.route) },
                )
            }
            composable(Destination.LISTS.route) { ListsScreen(viewModel) }
            composable(Destination.RULES.route) { RulesScreen(viewModel) }
            composable(Destination.LOG.route) { LogScreen(viewModel) }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenApps = { navController.navigate(ROUTE_APPS) },
                )
            }
            composable(ROUTE_APPS) {
                AppsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
