package io.github.swikrutd.swidscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
        enableEdgeToEdge()
        setContent { SwidTheme { AppNav() } }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(
            "camera?docId={docId}",
            arguments = listOf(navArgument("docId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { CameraScreen(nav, it.arguments?.getString("docId")) }
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
