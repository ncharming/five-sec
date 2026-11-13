package com.fivesec.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fivesec.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 全站统一弹窗外壳（ui/components 公共组件库成员）：
 *
 * 为什么不用各处裸的 M3 [androidx.compose.material3.AlertDialog]：
 *  - M3 AlertDialog 无进出场动画，出现/消失生硬；这里用 fade + scale 补齐（只动 opacity/scale，不掉帧）；
 *  - 默认容器色、内边距、按钮排布无法与白卡式页面语言对齐；这里把浮层定为与 [CardSurface]
 *    同语言的 surface 底 + hairline 描边 + 轻投影，容器色本身也已随 Theme.kt 品牌化。
 *
 * 设计约束：
 *  - visible 驱动而非调用方 if 条件挂载：退场动画必须在所有关闭路径（点遮罩、返回键、按钮）
 *    下都能播放，因此挂载状态由外壳自持，动画播完才卸载窗口并回调 [onDismissRequest]；
 *  - 退场动画期间按钮仍在屏幕上（只是淡出中），有副作用的确认动作由调用方以 visible 状态
 *    自行防重复提交（见 InterceptScreen 的 confirm 守卫）；
 *  - 圆角 24dp（比卡片 22dp 略大，强化浮层语义，与主题 shapes.extraLarge 同值）；
 *  - 左右 24dp 屏幕边距 + 480dp 最大宽度：手机上是贴齐页面边距的卡片式弹窗，平板不无限拉宽；
 *  - **标准弹窗窗口结构**（2026-11-13 修复「整块不可滚」）：标题与按钮区固定，中间是独立的
 *    **可垂直滚动内容区**——内容超出弹窗封顶高度（屏高 85%）时在弹窗内部上下滑动查看，
 *    而非像此前那样内容撑破窗口、底部被屏幕硬裁（待办编辑弹窗 020 起含模板/规则/提醒/日期
 *    多区，加上 DatePicker 日历在小屏机上必超屏高，被裁掉的日历底部整周「未来周六/日」点
 *    不到，观感即「只能选工作日」）。短内容不受影响：weight(fill=false) 有多少占多少，
 *    弹窗仍按内容自适应高度。
 */

/** 弹窗圆角：与主题 shapes.extraLarge 同值，卡片（22dp）之上再大一档的"浮层"语义。 */
private val DialogShape = RoundedCornerShape(24.dp)

/** 进出场时长（ms）：淡入比淡出略长，收得干脆、放得柔和。 */
private const val ENTER_MILLIS = 220
private const val EXIT_MILLIS = 160

/** 弹窗高度封顶占屏高比例：超出即转「内容区内部滚动」，保住浮层四周留白的卡片观感（不铺满屏）。 */
private const val MAX_HEIGHT_RATIO = 0.85f

/**
 * 统一弹窗：标题（固定）+ **独立可滚动内容槽** + 右对齐按钮区（固定；次按钮在左、主按钮在右）
 * ——标准弹窗窗口结构：内容超高时在弹窗内部上下滑动，标题与按钮始终可见。
 * [confirmButton]/[dismissButton] 是无约束槽位：主按钮用实心 [androidx.compose.material3.Button]、
 * 次按钮用 [androidx.compose.material3.TextButton]，由调用方按语义填充。
 */
@Composable
fun FiveSecDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    // mounted：Dialog 窗口是否挂载；targetVisible：动画目标态（true=展开，false=退场中）
    var mounted by remember { mutableStateOf(false) }
    var targetVisible by remember { mutableStateOf(false) }

    // 弹窗高度封顶（屏高 85%）：给「窗口结构」立边界，超出部分由内容区内部滚动消化
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_RATIO).dp

    // 可见态迁移：visible 置真一律展开（含退场中途快速重开：取消退场、原地转为入场）
    LaunchedEffect(visible) {
        when {
            visible -> {
                mounted = true
                targetVisible = true
            }

            mounted -> targetVisible = false
        }
    }
    // 退场动画播完再卸载窗口；点遮罩/返回键路径在此回调 onDismissRequest 通知调用方
    LaunchedEffect(targetVisible, mounted) {
        if (mounted && !targetVisible) {
            delay(EXIT_MILLIS.toLong())
            mounted = false
            onDismissRequest()
        }
    }

    if (!mounted) return
    Dialog(
        onDismissRequest = { targetVisible = false }, // 遮罩/返回键：先播退场动画
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = targetVisible,
                enter = fadeIn(tween(ENTER_MILLIS)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
                    ),
                exit = fadeOut(tween(EXIT_MILLIS)) +
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(EXIT_MILLIS, easing = FastOutSlowInEasing),
                    ),
            ) {
                Surface(
                    // 高度封顶（屏高 85%）：内容超高时不再撑破窗口被屏幕硬裁，而是收进下方
                    // 可滚动的独立内容区——「窗口结构」由这里先立住边界，weight 才有界可分
                    modifier = modifier
                        // widthIn 必须在 fillMaxWidth 之前：先封顶 480dp 再撑满，
                        // 否则 fillMaxWidth 先吃掉全部宽度，平板上卡片会被拉宽而内容仍居中
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight),
                    shape = DialogShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(top = Spacing.xl)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = Spacing.xl),
                        )
                        // 独立内容区（标准弹窗结构的中段）：weight(fill=false) = 有多少占多少、
                        // 超出封顶余量即收为滚动区——短弹窗观感与既往逐位一致，长表单/日历
                        // 在弹窗内部上下滑动，标题与按钮不被顶出屏
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(top = Spacing.md)
                                .padding(horizontal = Spacing.xl),
                            content = content,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.lg, bottom = Spacing.xl),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
