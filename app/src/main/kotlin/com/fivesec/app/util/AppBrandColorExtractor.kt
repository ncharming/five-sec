package com.fivesec.app.util

import android.content.Context
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 品牌色回退值（健康绿 0xFF00A86B）。 */
const val FALLBACK_BRAND_ARGB: Int = 0xFF00A86B.toInt()

val BLACK_ARGB: Int = 0xFF000000.toInt()
val WHITE_ARGB: Int = 0xFFFFFFFF.toInt()

private const val MIN_CONTRAST = 4.5 // WCAG AA 正常文本

/**
 * 从应用启动器图标提取品牌代表色（ARGB Int），按包名缓存。
 * 图标取不到、Palette 提取失败、或对比度均不达标时回退到 [FALLBACK_BRAND_ARGB]。
 * 颜色以 Int(ARGB) 返回，避免在本类引入 Compose 类型，便于纯 JVM 单测。
 */
@Singleton
class AppBrandColorExtractor @Inject constructor(
    @ApplicationContext private val app: Context,
) {
    private val cache = ConcurrentHashMap<String, Int>()

    suspend fun colorArgbFor(packageName: String): Int {
        cache[packageName]?.let { return it }
        val argb = extract(packageName)
        cache[packageName] = argb
        return argb
    }

    private suspend fun extract(packageName: String): Int = withContext(Dispatchers.IO) {
        val drawable = PackageUtil.icon(app.packageManager, packageName)
            ?: return@withContext FALLBACK_BRAND_ARGB
        val argb = try {
            val bitmap = drawable.toBitmap(width = 64, height = 64)
            val palette = Palette.from(bitmap).generate()
            palette.dominantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: FALLBACK_BRAND_ARGB
        } catch (e: Exception) {
            FALLBACK_BRAND_ARGB
        }
        if (hasUsableContrast(argb)) argb else FALLBACK_BRAND_ARGB
    }
}

/** 该背景色能否与黑或白达到 WCAG AA 对比度。 */
fun hasUsableContrast(argb: Int): Boolean =
    contrastRatio(argb, BLACK_ARGB) >= MIN_CONTRAST || contrastRatio(argb, WHITE_ARGB) >= MIN_CONTRAST

/** WCAG 相对亮度（0..1）。 */
fun luminance(argb: Int): Double {
    val r = linearize(((argb shr 16) and 0xFF) / 255.0)
    val g = linearize(((argb shr 8) and 0xFF) / 255.0)
    val b = linearize((argb and 0xFF) / 255.0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearize(c: Double): Double =
    if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

/** 两色之间的 WCAG 对比度（1..21）。 */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** 给定背景色，返回对比度更高的前景色（黑或白的 ARGB）。 */
fun onColorForBackground(bg: Int): Int =
    if (contrastRatio(bg, BLACK_ARGB) >= contrastRatio(bg, WHITE_ARGB)) BLACK_ARGB else WHITE_ARGB
