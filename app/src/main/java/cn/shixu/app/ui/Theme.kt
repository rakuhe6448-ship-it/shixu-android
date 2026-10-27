package cn.shixu.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.shixu.app.data.Preferences

val TaskColors = listOf(0xFF91A786,0xFF94B8CE,0xFFD4A58D,0xFFB3A2C9,0xFFD0BC73,0xFFD19BAA,0xFF7EB5AC,0xFF9DA9BE)

@Composable
fun ShiXuTheme(p: Preferences, content: @Composable () -> Unit) {
    val dark=p.theme=="DARK" || p.theme=="SYSTEM" && isSystemInDarkTheme()
    val activity=LocalActivity.current as? ComponentActivity
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle=SystemBarStyle.auto(0xE6FFFFFF.toInt(),0x801B1B1B.toInt()) { dark }
        )
        activity?.let { WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightNavigationBars=!dark }
    }
    val accent=Color(p.accent)
    val colors=if(dark) darkColorScheme(primary=Color(0xFFC0D3AE),onPrimary=Color(0xFF24321F),primaryContainer=Color(0xFF35482F),secondary=Color(0xFFC2C9B8),background=Color(0xFF171E19),surface=Color(0xFF1C251E),surfaceVariant=Color(0xFF303B30),onSurface=Color(0xFFE8EDE2),onSurfaceVariant=Color(0xFFBBC6B6),outlineVariant=Color(0xFF424D40))
    else lightColorScheme(primary=accent,onPrimary=Color.White,primaryContainer=Color(0xFFE4EBD9),onPrimaryContainer=Color(0xFF34442B),secondary=Color(0xFF79725C),secondaryContainer=Color(0xFFF0EAD7),background=Color(p.background),surface=Color(p.background),surfaceVariant=Color(0xFFEEF0E7),onSurface=Color(0xFF293227),onSurfaceVariant=Color(0xFF727B6B),outlineVariant=Color(0xFFDFE4D6))
    MaterialTheme(colorScheme=colors,typography=Typography(
        headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=30.sp,lineHeight=38.sp),
        headlineMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=25.sp,lineHeight=34.sp),
        titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=21.sp,lineHeight=29.sp),
        titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
        labelLarge=TextStyle(fontWeight=FontWeight.Medium,fontSize=14.sp)
    ),content=content)
}
