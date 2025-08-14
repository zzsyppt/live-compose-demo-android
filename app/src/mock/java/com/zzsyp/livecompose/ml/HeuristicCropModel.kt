package com.zzsyp.livecompose.ml

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

/**
 * 规则启发式假模型（更像真实效果）：
 * - 优先三分法落点（1/3 或 2/3），或在中心附近轻微偏移
 * - 框尺寸 0.55~0.80，避免边缘溢出
 * - 延时范围可配置
 */
class HeuristicCropModel(
    private val latencyMsRange: LongRange = 50L..90L
) : CropModel {

    override suspend fun predict(frame: Bitmap): CropResult = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtimeNanos()

        // 模拟推理延时
        delay(Random.nextLong(latencyMsRange.first, latencyMsRange.last + 1))

        // 三分法坐标或中心微偏移
        val thirds = listOf(1f / 3f, 2f / 3f)
        val cxCandidate = if (Random.nextBoolean())
            thirds.random()
        else
            0.5f + (Random.nextFloat() - 0.5f) * 0.12f

        val cyCandidate = if (Random.nextBoolean())
            thirds.random()
        else
            0.5f + (Random.nextFloat() - 0.5f) * 0.12f

        val w = 0.55f + Random.nextFloat() * 0.25f  // 0.55 ~ 0.80
        val h = 0.55f + Random.nextFloat() * 0.25f

        // 保证框不越界：cx,cy 限制到 [w/2, 1-w/2] / [h/2, 1-h/2]
        val cx = cxCandidate.coerceIn(w / 2f, 1f - w / 2f)
        val cy = cyCandidate.coerceIn(h / 2f, 1f - h / 2f)

        CropResult(
            cx = cx,
            cy = cy,
            w = w,
            h = h,
            score = 0.8f, // 启发式假定较高
            latencyMs = max(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        )
    }
}
