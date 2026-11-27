package com.metrolist.desktop.window

import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.shared.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Release builds record which classes the app loads while it walks through its main screens, and ship the JVM's
 * AOT cache built from that recording (see scripts/ondara-aot-cache.ps1). Loading those classes from the cache
 * makes startup and the first visit to each screen noticeably smoother. Only runs with -Dondara.aotTraining=true.
 */
internal object AotTraining {
    val enabled: Boolean = System.getProperty("ondara.aotTraining") == "true"

    private val route =
        listOf(
            Screen.Home,
            Screen.Library,
            Screen.Playlist("auto:downloaded"),
            Screen.Playlist("auto:liked"),
            Screen.Radio,
            Screen.History,
            Screen.Statistics,
            Screen.Search,
            Screen.Settings,
            Screen.Home,
        )

    suspend fun run() {
        delay(8_000)
        repeat(2) {
            route.forEach { screen ->
                runCatching { DesktopNavigation.navigate(screen) }
                delay(2_500)
            }
        }
        // A normal JVM exit writes the AOT configuration recorded during this run.
        exitProcess(0)
    }
}
