package com.fivesec.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.fivesec.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一视觉组件库（AppList 重设计确立的风格，四页面共用）：
 * - PageHeader：大标题页头（headlineMedium 加粗 + 可选副标语 + 右侧动作位）；
 * - CardSurface：白底 22dp 圆角卡片（hairline 描边 + 轻投影），承载各页内容区块；
 * - fiveSecSwitchColors：实心品牌绿开关（选中绿底白钮 / 未选中灰底白钮，无描边）；
 * - AppIcon：应用真实图标锚点（46dp 圆角，失败回退首字方块）；
 * - FiveSecTextFieldShape / fiveSecTextFieldColors：弹窗表单输入框统一形状与配色。
 */

/** 大标题页头：与 InterceptScreen 一致的页面入口样式。 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
        trailing()
    }
}

/**
 * 统一卡片容器：白底、22dp 圆角、hairline 描边、轻投影。
 * [onClick] 非空时用可点 Surface 重载（涟漪按圆角裁剪、带按钮语义）——统计页入口卡（specs/008）。
 */
@Composable
fun CardSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val color = MaterialTheme.colorScheme.surface
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            shadowElevation = 1.dp,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            shadowElevation = 1.dp,
        ) {
            Column(content = content)
        }
    }
}

/** 统一开关配色：实心品牌绿 / 灰底白钮，无描边（拦截总开关、应用行开关共用）。 */
@Composable
fun fiveSecSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedThumbColor = Color.White,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedThumbColor = Color.White,
    uncheckedBorderColor = Color.Transparent,
)

/** 统一输入框圆角：14dp 软圆角，衔接卡片（22dp）与浮层（24dp）的圆角语言；弹窗表单共用。 */
val FiveSecTextFieldShape = RoundedCornerShape(14.dp)

/**
 * 统一弹窗表单输入框配色：未聚焦描边弱化为发丝级（outline 45%），聚焦回到品牌绿——
 * 避免 M3 默认 outline 中灰在白卡弹窗里抢视觉。tips 与拦截应用弹窗共用。
 */
@Composable
fun fiveSecTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/**
 * 名额进度段：已用名额以品牌绿段展示（拦截应用 n/3 与待办 n/20 共用）。
 * segmentWidth 可调：段数越多段越窄（20 段待办用 10dp，3 段应用用默认 16dp）。
 */
@Composable
fun CapacitySegments(
    filled: Int,
    total: Int,
    segmentWidth: Dp = 16.dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(width = segmentWidth, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/**
 * 进程级图标位图缓存（LRU，2026-11）：PackageManager 图标解码（binder + bitmap）跨屏复用——
 * 拦截页/统计页的应用卡与应用选择列表共享。只在主线程读写（remember 读、LaunchedEffect 完成
 * 时写），无并发问题。
 */
private val appIconCache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 32
}

/** 应用图标锚点：读系统真实图标；加载失败回退为首字方块。dimmed 用于"已暂停"弱化。
 *  解码异步化（2026-11）：此前 remember{} 组合期同步 getApplicationIcon().toBitmap()——
 *  binder 调用 + 位图解码跑在主线程首帧路径；现在 IO 线程解码、就绪回填，期间显示回退底色。 */
@Composable
fun AppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    size: Dp = 46.dp,
) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(appIconCache[packageName]) }
    LaunchedEffect(packageName) {
        if (icon == null) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) appIconCache[packageName] = decoded
            icon = decoded
        }
    }
    // 局部快照供 smart cast（icon 是 var 委托属性，不能智能转换）
    val bitmap = icon
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (dimmed) 0.62f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = appName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                appName.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
