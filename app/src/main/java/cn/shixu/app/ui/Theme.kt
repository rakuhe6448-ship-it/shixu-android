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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import cn.shixu.app.data.Preferences
import cn.shixu.app.data.TaskPalette

val TaskColors = TaskPalette.colors

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
    val accent=if(dark) lerp(Color(p.accent),Color(0xFFF4EFDF),.32f) else Color(p.accent)
    val onAccent=if(accent.luminance()>.179f) Color(0xFF1E1D1A) else Color.White
    val colors=if(dark) darkColorScheme(primary=accent,onPrimary=onAccent,primaryContainer=Color(0xFF492C28),onPrimaryContainer=Color(0xFFFFD5CC),secondary=Color(0xFFE0BB70),secondaryContainer=Color(0xFF34312B),onSecondaryContainer=Color(0xFFF2EBDD),background=Color(0xFF191918),surface=Color(0xFF191918),surfaceContainerLow=Color(0xFF232321),surfaceContainer=Color(0xFF2C2B28),surfaceContainerHigh=Color(0xFF34332F),surfaceVariant=Color(0xFF35332F),onSurface=Color(0xFFF4EEDF),onSurfaceVariant=Color(0xFFC4BEB3),outlineVariant=Color(0xFF514D45))
    else lightColorScheme(primary=accent,onPrimary=onAccent,primaryContainer=Color(0xFFF0DDD4),onPrimaryContainer=Color(0xFF642A20),secondary=Color(0xFF242421),secondaryContainer=Color(0xFFE7DFCB),onSecondaryContainer=Color(0xFF29271F),background=Color(p.background),surface=Color(p.background),surfaceContainerLow=Color(0xFFFAF6EB),surfaceContainer=Color(0xFFEDE6D8),surfaceContainerHigh=Color(0xFFE5DDCD),surfaceVariant=Color(0xFFE8E1D2),onSurface=Color(0xFF242421),onSurfaceVariant=Color(0xFF666154),outlineVariant=Color(0xFFD1C8B7))
    MaterialTheme(colorScheme=colors,shapes=Shapes(extraSmall=RoundedCornerShape(2.dp),small=RoundedCornerShape(4.dp),medium=RoundedCornerShape(6.dp),large=RoundedCornerShape(8.dp),extraLarge=RoundedCornerShape(12.dp)),typography=Typography(
        headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Black,fontSize=32.sp,lineHeight=40.sp,letterSpacing=(-1).sp),
        headlineMedium=TextStyle(fontWeight=FontWeight.ExtraBold,fontSize=27.sp,lineHeight=35.sp,letterSpacing=(-.6).sp),
        titleLarge=TextStyle(fontWeight=FontWeight.Bold,fontSize=21.sp,lineHeight=29.sp),
        titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
        labelLarge=TextStyle(fontWeight=FontWeight.Bold,fontSize=13.sp,letterSpacing=.4.sp),
        labelSmall=TextStyle(fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,fontSize=10.sp,letterSpacing=1.sp)
    ),content=content)
}
