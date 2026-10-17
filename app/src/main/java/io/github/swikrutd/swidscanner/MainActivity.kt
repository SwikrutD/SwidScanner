package io.github.swikrutd.swidscanner

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.data.ThemeMode
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.imaging.MlKitScan
import io.github.swikrutd.swidscanner.ui.CameraScreen
import io.github.swikrutd.swidscanner.ui.CropScreen
import io.github.swikrutd.swidscanner.ui.DocScreen
import io.github.swikrutd.swidscanner.ui.HomeScreen
import io.github.swikrutd.swidscanner.ui.OcrScreen
import io.github.swikrutd.swidscanner.ui.PageScreen
import io.github.swikrutd.swidscanner.ui.PlaceOverlayScreen
import io.github.swikrutd.swidscanner.ui.QrScreen
import io.github.swikrutd.swidscanner.ui.Routes
import io.github.swikrutd.swidscanner.ui.SettingsScreen
import io.github.swikrutd.swidscanner.ui.SignatureDrawScreen
import io.github.swikrutd.swidscanner.ui.AssetImportScreen
import io.github.swikrutd.swidscanner.ui.AssetLibraryScreen
import io.github.swikrutd.swidscanner.ui.StampTextScreen
import io.github.swikrutd.swidscanner.ui.SwidTheme

class MainActivity : ComponentActivity() {
    /** Screen to open on top of the current one, from the Scan shortcut, the quick settings tile or adb. */
    private var route by mutableStateOf<String?>(null)

    /** The optional Google scanner. Registers its result launcher, so it has to exist before the activity starts. */
    private val mlKit = MlKitScan(this) { r -> runOnUiThread { route = r } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route = routeFor(intent)
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
            SwidTheme(dark) { AppNav(route, mlKit) { route = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { route = it }
    }

    private fun routeFor(intent: Intent?): String? = when {
        intent?.action == ACTION_SCAN -> Routes.camera()
        // Debug and bench builds: `adb shell am start -n <pkg>/io.github.swikrutd.swidscanner.MainActivity --es route camera`
        BuildConfigInfo.isDev -> intent?.getStringExtra("route")
        else -> null
    }

    companion object {
        /** Opens the camera for a new document. */
        const val ACTION_SCAN = "io.github.swikrutd.swidscanner.SCAN"

        fun scanIntent(ctx: Context): Intent = Intent(ACTION_SCAN).setClass(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

// The scrims enableEdgeToEdge() uses by default for 3-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

@Composable
private fun AppNav(route: String?, mlKit: MlKitScan, onRouted: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val busy by mlKit.busy.collectAsState()
    LaunchedEffect(route) {
        if (route != null) { nav.navigate(route); onRouted() }
    }
    LaunchedEffect(mlKit) { mlKit.notices.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav, mlKit) }
        composable(
            "camera?docId={docId}&retake={retake}&thenDoc={thenDoc}&asset={asset}&id={id}",
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("retake") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("thenDoc") { type = NavType.BoolType; defaultValue = false },
                navArgument("asset") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("id") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            val a = it.arguments
            CameraScreen(
                nav, a?.getString("docId"), a?.getString("retake"), a?.getBoolean("thenDoc") ?: false,
                assetKindArg(a?.getString("asset")), a?.getBoolean("id") ?: false,
            )
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
        composable(
            Routes.PLACE,
            arguments = listOf(
                navArgument("asset") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("overlay") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            val a = it.arguments!!
            PlaceOverlayScreen(nav, a.getString("docId")!!, a.getString("pageId")!!, a.getString("asset"), a.getString("overlay"))
        }
        composable(Routes.QR) { QrScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.LIBRARY) { e -> assetKindArg(e.arguments?.getString("kind"))?.let { AssetLibraryScreen(nav, it) } }
        composable(Routes.SIGNATURE_DRAW) { SignatureDrawScreen(nav) }
        composable(Routes.STAMP_TEXT) { StampTextScreen(nav) }
        composable(
            Routes.ASSET_IMPORT,
            arguments = listOf(navArgument("window") { type = NavType.BoolType; defaultValue = false }),
        ) { e ->
            assetKindArg(e.arguments?.getString("kind"))?.let { AssetImportScreen(nav, it, e.arguments?.getBoolean("window") ?: false) }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    if (busy) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)).pointerInput(Unit) {}, Alignment.Center) {
            CircularProgressIndicator()
        }
    }
    }
}

private fun assetKindArg(name: String?): AssetKind? = AssetKind.entries.firstOrNull { it.name == name }
