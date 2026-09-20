package com.juwp.schedule.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 触发切页所需的横向拖动距离 */
private val SWIPE_THRESHOLD = 72.dp

/**
 * 让**非课表页**也能左右滑动切到邻页。
 *
 * 用户要求：在「今日」和「设置」里左右滑动可以到达「周课表」。
 * 这里做成通用工具，方向由调用方决定（今日页是 tab 0，左滑到 tab 1；
 * 设置页是 tab 2，右滑到 tab 1）。
 *
 * ⚠️ 为什么用 [detectHorizontalDragGestures] 而不是 `draggable` / `swipeable`：
 *   - `detectHorizontalDragGestures` 只在**横向**超过 touch slop 后才接管手势，
 *     纵向滑动完全不受影响 —— 这两页本身都是纵向滚动列表，
 *     用别的 API 很容易把滚动吃掉或和滚动抢手势。
 *   - 周课表页**不加**这个修饰符：那一页里左右滑动是「翻周」，
 *     两者会直接冲突。
 *
 * @param onSwipeLeft 手指从右往左滑（内容向左走）时触发，一般是「去后一个 tab」
 * @param onSwipeRight 手指从左往右滑时触发，一般是「去前一个 tab」
 */
fun Modifier.swipeToAdjacentTab(
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
): Modifier = this.pointerInput(onSwipeLeft, onSwipeRight) {
    // PointerInputScope 本身就是 Density，可以直接把 dp 换成像素
    val threshold = SWIPE_THRESHOLD.toPx()
    var total = 0f

    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onHorizontalDrag = { _, dragAmount -> total += dragAmount },
        onDragEnd = {
            when {
                total <= -threshold -> onSwipeLeft?.invoke()
                total >= threshold -> onSwipeRight?.invoke()
            }
        },
    )
}
