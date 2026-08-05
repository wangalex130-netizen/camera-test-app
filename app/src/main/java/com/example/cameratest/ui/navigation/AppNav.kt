package com.example.cameratest.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameratest.ui.screens.HistoryScreen
import com.example.cameratest.ui.screens.LanScreen
import com.example.cameratest.ui.screens.ManualScreen
import com.example.cameratest.ui.screens.WanScreen
import com.example.cameratest.viewmodel.AppViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Lan : Screen("lan", "内网", Icons.Filled.Home)
    data object Wan : Screen("wan", "外网", Icons.Filled.Language)
    data object Manual : Screen("manual", "手动", Icons.Filled.Tune)
    data object History : Screen("history", "历史", Icons.Filled.History)
}

@Composable
fun AppNav(vm: AppViewModel = viewModel()) {
    var route by remember { mutableStateOf(Screen.Lan.route) }
    val items = listOf(Screen.Lan, Screen.Wan, Screen.Manual, Screen.History)

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { s ->
                    NavigationBarItem(
                        selected = route == s.route,
                        onClick = { route = s.route },
                        icon = { Icon(s.icon, contentDescription = s.label) },
                        label = { Text(s.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (route) {
                Screen.Lan.route -> LanScreen(vm)
                Screen.Wan.route -> WanScreen(vm)
                Screen.Manual.route -> ManualScreen(vm)
                Screen.History.route -> HistoryScreen(vm)
            }
        }
    }
}
