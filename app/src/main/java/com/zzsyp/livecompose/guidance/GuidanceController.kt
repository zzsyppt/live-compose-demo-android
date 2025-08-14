package com.zzsyp.livecompose.guidance

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.sqrt

enum class ShakeLevel { Stable, Wobbly, Shaky }

data class GuidanceState(
    val box: RectF,         // 归一化 LTRB
    val centerDist: Float,  // 与屏幕中心距离（0~≈0.71）
    val level: ShakeLevel,
    val shouldSnap: Boolean,// 允许自动快门/震动
    val zSuggested: Float   // 建议等效变焦
)

class GuidanceController(
    private val lockRadius: Float = 0.06f, // 进入“贴合”的中心圈半径
    private val lockHoldMs: Long = 300L    // 贴合需保持的时间
) {
    private var enterTs: Long = -1L

    fun update(box: RectF, shake: ShakeLevel, nowMs: Long): GuidanceState {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val dist = hypot(cx - 0.5f, cy - 0.5f)
        val area = (box.right - box.left) * (box.bottom - box.top)
        val z = 1f / sqrt(area.coerceIn(1e-6f, 1f))

        val inRadius = dist <= lockRadius
        val stable = (shake == ShakeLevel.Stable)

        if (inRadius && stable) {
            if (enterTs < 0) enterTs = nowMs
        } else enterTs = -1L

        val held = (enterTs > 0 && nowMs - enterTs >= lockHoldMs)

        return GuidanceState(
            box = box, centerDist = dist, level = shake,
            shouldSnap = held, zSuggested = z
        )
    }
}
