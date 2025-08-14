package com.zzsyp.livecompose.camera

import android.content.Context
import android.view.ScaleGestureDetector
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class CameraUseCases(
    val preview: Preview,
    val imageCapture: ImageCapture,
    val imageAnalysis: ImageAnalysis,
    val camera: Camera
)

/**
 * 绑定 CameraX 三件套：Preview / ImageCapture / ImageAnalysis
 * - 预览：16:9（铺屏友好）
 * - 拍照：4:3（更贴近全感光元件）
 * - 分析：RGBA_8888（便于直接转 Bitmap）
 */
suspend fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer
): CameraUseCases {
    val provider = getCameraProvider(context)

    val preview = Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

    val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build()

    val imageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setTargetAspectRatio(AspectRatio.RATIO_4_3) // ← 新增：与拍照 4:3 对齐，便于归一化坐标复用
        .build().also {
            it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
        }

    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    provider.unbindAll()
    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, imageAnalysis)

    // 手势缩放（在 PreviewView 上捏合即可缩放）
    val detector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val info = camera.cameraInfo.zoomState.value ?: return false
                val current = info.zoomRatio
                val max = info.maxZoomRatio
                val min = info.minZoomRatio
                val next = (current * detector.scaleFactor).coerceIn(min, max)
                camera.cameraControl.setZoomRatio(next)
                return true
            }
        })
    previewView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

    return CameraUseCases(preview, imageCapture, imageAnalysis, camera)
}

private suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(context)
        )
    }
