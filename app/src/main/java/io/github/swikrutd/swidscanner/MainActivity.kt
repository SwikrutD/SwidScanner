package io.github.swikrutd.swidscanner

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.data.ThemeMode
import io.github.swikrutd.swidscanner.ui.CameraScreen
import io.github.swikrutd.swidscanner.ui.CropScreen
import io.github.swikrutd.swidscanner.ui.DocScreen
import io.github.swikrutd.swidscanner.ui.HomeScreen
import io.github.swikrutd.swidscanner.ui.OcrScreen
import io.github.swikrutd.swidscanner.ui.PageScreen
import io.github.swikrutd.swidscanner.ui.QrScreen
import io.github.swikrutd.swidscanner.ui.Routes
import io.github.swikrutd.swidscanner.ui.SettingsScreen
import io.github.swikrutd.swidscanner.ui.SwidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by Settings.theme.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            SwidTheme(dark) { AppNav(intent?.getStringExtra("route")) }
        }
    }
}

// The scrims enableEdgeToEdge() uses by default for 3-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

@Composable
private fun AppNav(startRoute: String?) {
    val nav = rememberNavController()
    // Debug and bench builds: `adb shell am start -n <pkg>/io.github.swikrutd.swidscanner.MainActivity --es route camera`
    // opens a screen directly.
    LaunchedEffect(startRoute) { if (BuildConfigInfo.isDev && startRoute != null) nav.navigate(startRoute) }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(
            "camera?docId={docId}&retake={retake}&thenDoc={thenDoc}",
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("retake") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("thenDoc") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            val a = it.arguments
            CameraScreen(nav, a?.getString("docId"), a?.getString("retake"), a?.getBoolean("thenDoc") ?: false)
        }
        composable(
            "crop/{docId}/{pageId}?thenDoc={thenDoc}",
            arguments = listOf(navArgument("thenDoc") { type = NavType.BoolType; defaultValue = false }),
        ) {
            val a = it.arguments!!
            CropScreen(nav, a.getString("docId")!!, a.getString("pageId")!!, a.getBoolean("thenDoc"))
        }
        composable("doc/{docId}") { DocScreen(nav, it.arguments!!.getString("docId")!!) }
        composable("page/{docId}/{pageId}") {
            PageScreen(nav, it.arguments!!.getString("docId")!!, it.arguments!!.getString("pageId")!!)
        }
        composable("ocr/{docId}/{pageId}") {
            OcrScreen(nav, it.arguments!!.getString("docId")!!, it.arguments!!.getString("pageId")!!)
        }
        composable(Routes.QR) { QrScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
    }
}
