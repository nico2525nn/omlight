package com.omlight.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5DA7), // OM blue, slightly deepened for contrast
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E5FF),
    onPrimaryContainer = Color(0xFF0B305F),
    secondary = Color(0xFF4F616D),
    secondaryContainer = Color(0xFFD2E5F1),
    tertiary = Color(0xFF6A5D8F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0B305F),
    primaryContainer = Color(0xFF22467E),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFFB6C9D6),
    secondaryContainer = Color(0xFF3A4A55),
    tertiary = Color(0xFFCABFF0),
)

/**
 * M3 Expressive theme. Dynamic color on Android 12+ (user wallpaper),
 * static OM-blue scheme below. Expressive motion + shapes come from
 * [MaterialExpressiveTheme].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OMTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
