package com.zzsyp.livecompose.util

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * 将任意归一化 LTRB 矩形最小扩张为指定长宽比（不缩小，只扩张），并保证仍在 [0,1] 范围内。
 * @param box  归一化 LTRB
 * @param aspect  目标长宽比（宽/高）
 * @return 归一化 LTRB
 */
fun expandToAspectMin(box: RectF, aspect: Float): RectF {
    var w = box.width()
    var h = box.height()
    val cx = (box.left + box.right) / 2f
    val cy = (box.top + box.bottom) / 2f
    val curAspect = w / h

    if (curAspect < aspect) {
        // 需要更宽
        w = h * aspect
    } else if (curAspect > aspect) {
        // 需要更高
        h = w / aspect
    }
    var l = cx - w / 2f
    var r = cx + w / 2f
    var t = cy - h / 2f
    var b = cy + h / 2f

    // 若越界，整体平移回边界（保持大小不变）
    val dxLeft = min(0f, l)
    val dxRight = max(0f, r - 1f)
    val dyTop = min(0f, t)
    val dyBottom = max(0f, b - 1f)
    l -= (dxLeft + dxRight)
    r -= (dxLeft + dxRight)
    t -= (dyTop + dyBottom)
    b -= (dyTop + dyBottom)

    return RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

/**
 * 在候选比例集中选择“最小扩张面积”的结果。
 * @param candidates 比例数组（宽/高）
 * @return 归一化 LTRB（最佳）
 */
fun snapToBestAspect(box: RectF, candidates: List<Float>): RectF {
    var best: RectF = box
    var bestIncrease = Float.POSITIVE_INFINITY
    val baseArea = box.width() * box.height()

    for (a in candidates) {
        val r = expandToAspectMin(box, a)
        val inc = (r.width() * r.height()) / baseArea
        if (inc < bestIncrease) {
            bestIncrease = inc
            best = r
        }
    }
    return best
}
