package com.fivesec.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 健康绿品牌色板 —— 全 app 单一事实来源。
 * Light + Dark 两套完整 M3 角色，Theme.kt 据此构建 colorScheme。
 * 拦截覆盖层（命令式 View）需要的几个色同步进 res/values/colors.xml，保持同源。
 *
 * surfaceContainer 系列是 M3 浮层（弹窗/菜单/底部抽屉）的默认容器色：
 * 取 Surface(FFFFFF/0E1A14) 与 SurfaceVariant(DCE5DE/404943) 之间的绿灰过渡阶，
 * 不覆盖会回退 baseline 紫灰，破坏品牌一致。
 */

// region Light
val Primary = Color(0xFF00A86B)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFCFF5E2)
val OnPrimaryContainer = Color(0xFF00381E)
val Secondary = Color(0xFF4C6357)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFCEE9DB)
val OnSecondaryContainer = Color(0xFF092016)
val Tertiary = Color(0xFF426278)
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFFC7E8FF)
val OnTertiaryContainer = Color(0xFF001E2F)
val Error = Color(0xFFBA1A1A)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)
val Background = Color(0xFFF6F8F6)
val OnBackground = Color(0xFF0E1A14)
val Surface = Color(0xFFFFFFFF)
val OnSurface = Color(0xFF0E1A14)
val SurfaceVariant = Color(0xFFDCE5DE)
val OnSurfaceVariant = Color(0xFF5B6B62)
val Outline = Color(0xFF707972)
val OutlineVariant = Color(0xFFDDE6E0)
val SurfaceDim = Color(0xFFD9E0DB)
val SurfaceBright = Color(0xFFF9FBF9)
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF3F7F4)
val SurfaceContainer = Color(0xFFEDF2EE)
val SurfaceContainerHigh = Color(0xFFE7EDE8)
val SurfaceContainerHighest = Color(0xFFE1E8E3)
val SurfaceTint = Color(0xFF00A86B)
val InverseSurface = Color(0xFF2E322F)
val InverseOnSurface = Color(0xFFEFF1ED)
val InversePrimary = Color(0xFF6FE0AE)
val Scrim = Color(0xFF000000)
// endregion

// region Dark
val PrimaryDark = Color(0xFF6FE0AE)
val OnPrimaryDark = Color(0xFF00381E)
val PrimaryContainerDark = Color(0xFF005137)
val OnPrimaryContainerDark = Color(0xFF9CF0C6)
val SecondaryDark = Color(0xFFB2CDBE)
val OnSecondaryDark = Color(0xFF1D352A)
val SecondaryContainerDark = Color(0xFF354B40)
val OnSecondaryContainerDark = Color(0xFFCEE9DB)
val TertiaryDark = Color(0xFFAACBE3)
val OnTertiaryDark = Color(0xFF0A354A)
val TertiaryContainerDark = Color(0xFF284B61)
val OnTertiaryContainerDark = Color(0xFFC7E8FF)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val BackgroundDark = Color(0xFF0E1A14)
val OnBackgroundDark = Color(0xFFDDE5DE)
val SurfaceDark = Color(0xFF0E1A14)
val OnSurfaceDark = Color(0xFFDDE5DE)
val SurfaceVariantDark = Color(0xFF404943)
val OnSurfaceVariantDark = Color(0xFFBFCBC4)
val OutlineDark = Color(0xFF8A938D)
val OutlineVariantDark = Color(0xFF404943)
val SurfaceDimDark = Color(0xFF0E1A14)
val SurfaceBrightDark = Color(0xFF35403A)
val SurfaceContainerLowestDark = Color(0xFF09120D)
val SurfaceContainerLowDark = Color(0xFF161F1A)
val SurfaceContainerDark = Color(0xFF1A241E)
val SurfaceContainerHighDark = Color(0xFF242E28)
val SurfaceContainerHighestDark = Color(0xFF2F3933)
val SurfaceTintDark = Color(0xFF6FE0AE)
val InverseSurfaceDark = Color(0xFFEFF1ED)
val InverseOnSurfaceDark = Color(0xFF2E322F)
val InversePrimaryDark = Color(0xFF00A86B)
val ScrimDark = Color(0xFF000000)
// endregion
