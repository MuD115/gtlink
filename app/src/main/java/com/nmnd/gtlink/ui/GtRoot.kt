package com.nmnd.gtlink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmnd.gtlink.ui.screens.AlarmsScreen
import com.nmnd.gtlink.ui.screens.ContactsScreen
import com.nmnd.gtlink.ui.screens.DeveloperScreen
import com.nmnd.gtlink.ui.screens.HistoryScreen
import com.nmnd.gtlink.ui.screens.InstallScreen
import com.nmnd.gtlink.ui.screens.NotificationsScreen
import com.nmnd.gtlink.ui.screens.SettingsScreen
import com.nmnd.gtlink.ui.screens.WatchFacesScreen
import com.nmnd.gtlink.ui.screens.WeatherScreen

enum class Route { Home, Pairing, Permissions, Settings, Notifications, Alarms, WatchFaces, Contacts, Weather, Install, History, Developer }

private data class Dest(val route: Route, val depth: Int)

@Composable
fun GtRoot(vm: AppViewModel) {
    val saved by vm.savedWatch.collectAsStateWithLifecycle()

    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) {
        mutableStateListOf((if (vm.savedWatch.value == null) Route.Pairing else Route.Home).name)
    }

    val current = Route.entries.firstOrNull { it.name == stack.lastOrNull() } ?: Route.Home

    fun push(route: Route) {
        if (stack.lastOrNull() != route.name) stack.add(route.name)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun reset(route: Route) {
        stack.clear()
        stack.add(route.name)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Forgetting the watch (or a factory reset) sends you back to pairing from anywhere.
    LaunchedEffect(saved) {
        if (saved == null && current != Route.Pairing && current != Route.Permissions) reset(Route.Pairing)
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    val holder = rememberSaveableStateHolder()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen owns its own top bar and insets; the root only hosts the snackbar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        },
    ) { outer ->
        Box(Modifier.fillMaxSize().padding(outer)) {
            AnimatedContent(
                targetState = Dest(current, stack.size),
                contentKey = { it.route },
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    val shift = if (forward) 1 else -1
                    (slideInHorizontally(tween(260)) { it / 10 * shift } + fadeIn(tween(220))) togetherWith
                        fadeOut(tween(120))
                },
                label = "route",
            ) { dest ->
                holder.SaveableStateProvider(dest.route.name) {
                    when (dest.route) {
                        Route.Home -> HomeScreen(vm, onNavigate = ::push)
                        Route.Pairing -> PairingScreen(
                            vm,
                            onPaired = { reset(Route.Home) },
                            onBack = if (stack.size > 1) ({ pop() }) else null,
                        )
                        Route.Permissions -> PermissionsScreen(onBack = ::pop)
                        Route.Settings -> SettingsScreen(vm, onBack = ::pop)
                        Route.Notifications -> NotificationsScreen(vm, onBack = ::pop)
                        Route.Alarms -> AlarmsScreen(vm, onBack = ::pop)
                        Route.WatchFaces -> WatchFacesScreen(vm, onBack = ::pop)
                        Route.Contacts -> ContactsScreen(vm, onBack = ::pop)
                        Route.Weather -> WeatherScreen(vm, onBack = ::pop)
                        Route.Install -> InstallScreen(vm, onBack = ::pop)
                        Route.History -> HistoryScreen(vm, onBack = ::pop)
                        Route.Developer -> DeveloperScreen(vm, onBack = ::pop)
                    }
                }
            }
        }
    }
}
