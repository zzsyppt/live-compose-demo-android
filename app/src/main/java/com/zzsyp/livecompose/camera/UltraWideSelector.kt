package com.zzsyp.livecompose.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * 选择后置摄像头中“可用焦距最短”的那一颗（优先超广角）。
 *
 * 实现思路：
 * - 通过 Camera2CameraInfo 读取每个后置摄像头的 LENS_INFO_AVAILABLE_FOCAL_LENGTHS；
 * - 取其最小焦距作为排序键，最小者优先；
 * - 若读取失败或没有数据，则把该相机排到最后（Float.MAX_VALUE）。
 *
 * 说明：
 * - 有些机型可能把“微距”也作为独立后置相机报告，焦距也可能很短；若后续发现误选微距，
 *   我们可以在此处再加约束（例如最小对焦距离、传感器尺寸/FOV 推断等）进行过滤。
 */
fun ultraWideBackSelector(): CameraSelector {
    return CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos: List<CameraInfo> ->
            if (infos.isEmpty()) return@addCameraFilter infos

            val sorted = infos.sortedBy { info ->
                try {
                    val c2 = Camera2CameraInfo.from(info)
                    val arr = c2.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )
                    // 用最小的焦距作为排序依据；越小越“广”
                    arr?.minOrNull() ?: Float.MAX_VALUE
                } catch (_: Throwable) {
                    // 读不到就排后面
                    Float.MAX_VALUE
                }
            }
            // 只保留最广的那一颗
            listOf(sorted.first())
        }
        .build()
}
