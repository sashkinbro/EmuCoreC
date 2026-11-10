package com.sbro.emucorec.ui.common

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Applies the app-wide immersive policy.
 *
 * System UI can be restored by focus changes, dialogs, the IME, or vendor
 * firmware, so activities call this again when they resume or regain focus.
 */
internal object ImmersiveMode {
    @Suppress("DEPRECATION")
    fun apply(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Applies the same immersive policy to the window of the Compose dialog this is composed into.
 *
 * A dialog is its own window, and that window is not immersive, so the status and navigation
 * bars pop up over a full-screen game as soon as a confirmation opens (the exit dialog was the
 * reported case). Place this inside the dialog content; it draws nothing. The activity window
 * keeps its own immersive state, so dismissing the dialog leaves the game untouched.
 */
@Suppress("DEPRECATION")
@Composable
internal fun ImmersiveDialogWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        // The view already lives in the dialog's own window, so drive that window's bars:
        // legacy flags below API 30, the insets controller from 30 up.
        val root = view.rootView
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        ViewCompat.getWindowInsetsController(root)?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
