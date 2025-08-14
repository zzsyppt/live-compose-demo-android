package com.zzsyp.livecompose.ml

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * 随机框 + 可控延时的假模型：
 * - 输出 (cx, cy, w, h) 皆为 [0,1]
 * - 通过 latencyMsRange 模拟端侧推理耗时
 */
class RandomCropModel(
    private val latencyMsRange: LongRange = 40L..80L,
    seed: Long = 42L
) : CropModel {

    private val rng = Random(seed)

    override suspend fun predict(frame: Bitmap): CropResult = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtimeNanos()

        // 1) 模拟推理延时（带抖动）
        val delayMs = latencyMsRange.first +
                rng.nextInt((latencyMsRange.last - latencyMsRange.first + 1).toInt())
        delay(delayMs)

        // 2) 生成随机但合理的框（避免过小/过大）
        val w = 0.45f + rng.nextFloat() * 0.35f   // 0.45 ~ 0.80
        val h = 0.45f + rng.nextFloat() * 0.35f
        val cx = (w / 2f) + rng.nextFloat() * (1f - w) // 保证不越界
        val cy = (h / 2f) + rng.nextFloat() * (1f - h)

        CropResult(
            cx = cx,
            cy = cy,
            w = w,
            h = h,
            score = rng.nextFloat(),
            latencyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        )
    }
}
