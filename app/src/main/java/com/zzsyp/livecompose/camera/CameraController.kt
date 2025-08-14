package com.zzsyp.livecompose.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class CameraUseCases(
    val preview: Preview,
    val imageCapture: ImageCapture,
    val imageAnalysis: ImageAnalysis,
    val camera: Camera
)

suspend fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer
): CameraUseCases {
    val provider = getCameraProvider(context)

    // 与 PreviewView 保持一致的旋转
    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

    val preview = Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .setTargetRotation(rotation)
        .build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

    val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .setTargetRotation(rotation)
        .build()

    val imageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .setTargetRotation(rotation)
        .build().also {
            // 后台线程跑分析，避免阻塞主线程
            val exec = Executors.newSingleThreadExecutor()
            it.setAnalyzer(exec, analyzer)
        }

    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    provider.unbindAll()
    val camera = provider.bindToLifecycle(
        lifecycleOwner, selector, preview, imageCapture, imageAnalysis
    )

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
