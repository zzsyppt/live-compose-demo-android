package com.zzsyp.livecompose.camera

import androidx.camera.core.Camera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 轻度变焦控制：支持将目标“等效变焦”平滑过渡到摄像头缩放。
 */
class ZoomControl(
    private val camera: Camera,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    /**
     * 根据裁切框面积估算等效变焦：z ≈ 1 / sqrt(area)
     * - area 为归一化面积（0~1）
     * - 当 z 低于阈值时不触发（避免轻微裁切就频繁缩放）
     */
    fun applyZoomForArea(area: Float, threshold: Float = 1.5f, steps: Int = 6, stepDelayMs: Long = 16L) {
        val z = 1f / sqrt(area.coerceIn(1e-6f, 1f))
        if (z < threshold) return
        rampTo(z, steps, stepDelayMs)
    }

    /** 将当前 zoomRatio 平滑过渡到 targetRatio */
    fun rampTo(targetRatio: Float, steps: Int = 6, stepDelayMs: Long = 16L) {
        val info = camera.cameraInfo.zoomState.value ?: return
        val min = info.minZoomRatio
        val max = info.maxZoomRatio
        val target = targetRatio.coerceIn(min, max)

        job?.cancel()
        job = scope.launch {
            val start = info.zoomRatio
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val z = start + (target - start) * t
                camera.cameraControl.setZoomRatio(z)
                delay(stepDelayMs)
            }
        }
    }

    suspend fun rampToAndWait(targetRatio: Float, steps: Int = 10, stepDelayMs: Long = 16L) {
        val info = camera.cameraInfo.zoomState.value ?: return
        val min = info.minZoomRatio
        val max = info.maxZoomRatio
        val target = targetRatio.coerceIn(min, max)
        val start = info.zoomRatio
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val z = start + (target - start) * t
            camera.cameraControl.setZoomRatio(z)
            kotlinx.coroutines.delay(stepDelayMs)
        }
    }

    fun currentZoom(): Float? = camera.cameraInfo.zoomState.value?.zoomRatio
    suspend fun resetTo(base: Float = 1.0f, steps: Int = 10, stepDelayMs: Long = 16L) {
        rampToAndWait(base, steps, stepDelayMs)
    }


}
