package com.zzsyp.livecompose.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.zzsyp.livecompose.camera.*
import com.zzsyp.livecompose.guidance.*
import com.zzsyp.livecompose.ml.CropResult
import com.zzsyp.livecompose.ml.CropModel
import com.zzsyp.livecompose.ml.ModelProvider
import com.zzsyp.livecompose.util.*
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.sqrt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect

/**
 * 该文件实现了直播应用中的相机功能界面，是应用的核心相机交互模块。
 *
 * 主要功能包括：
 * 1. 相机权限请求与CameraX组件初始化，实现相机预览功能
 * 2. 集成帧分析器处理相机预览帧，结合机器学习模型进行实时图像处理
 * 3. 实现设备抖动检测功能，感知设备状态变化
 * 4. 提供自动对焦和缩放控制逻辑，优化拍摄体验
 * 5. 实现拍摄操作与视觉反馈（包括奖励动画效果）
 * 6. 展示相机性能指标（延迟、帧率等调试信息）
 *
 * 界面包含相机预览区域、实时检测框、操作状态指示及调试信息显示，
 * 通过组合多个自定义组件构建完整的相机交互体验。
 */
@Composable
fun CameraScreen(flavor: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // 设备抖动检测
    // 设备抖动检测（用 DisposableEffect 更稳）
    var level by remember { mutableStateOf(ShakeLevel.Stable) }
    DisposableEffect(Unit) {
        val det = ShakeDetector(context) { level = it }
        det.start()
        onDispose { det.stop() }
    }

    // 相机与可变状态
    var cameraUse by remember { mutableStateOf<CameraUseCases?>(null) }
    var contentRectPx by remember { mutableStateOf(RectF()) }

    // 模型（来自 flavor 的 provider）
    val model: CropModel = remember { ModelProvider.provide() }

    val rate = remember { RateLimiter(maxHz = 15f) }
    val ema = remember { EmaBox(alpha = 0.6f) }
    val controller = remember { GuidanceController() }
    val zoomCtl = remember(cameraUse) { cameraUse?.let { ZoomControl(it.camera, scope) } }

    // 统计
    var lastLatency by remember { mutableLongStateOf(0L) }
    var feedHz by remember { mutableFloatStateOf(0f) }
    var inferHz by remember { mutableFloatStateOf(0f) }
    var lastFeedNs by remember { mutableLongStateOf(0L) }
    var lastInferNs by remember { mutableLongStateOf(0L) }

    // UI 状态
    var boxNorm by remember { mutableStateOf(RectF(0.2f,0.2f,0.8f,0.8f)) }
    var locked by remember { mutableStateOf(false) }
    var showReward by remember { mutableStateOf(false) }

    // 权限
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { req.launch(Manifest.permission.CAMERA) }

    // 预览视图与 CameraX 绑定
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PreviewView(it).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { pv ->
            if (cameraUse == null) {
                scope.launch {
                    val analyzer = FrameAnalyzer(
                        onFrame = { bmp: Bitmap ->
                            val now = System.nanoTime()
                            if (lastFeedNs > 0) feedHz = 1e9f / (now - lastFeedNs)
                            lastFeedNs = now
                            if (!rate.tryPass(now)) return@FrameAnalyzer

                            scope.launch(Dispatchers.Default) {
                                val t0 = System.nanoTime()
                                val res: CropResult = model.predict(bmp)
                                lastLatency = (System.nanoTime() - t0) / 1_000_000

                                val now2 = System.nanoTime()
                                if (lastInferNs > 0) inferHz = 1e9f / (now2 - lastInferNs)
                                lastInferNs = now2

                                val smoothed = ema.smooth(RectNorm(res.cx, res.cy, res.w, res.h))
                                val ltrb = RectF(
                                    smoothed.cx - smoothed.w/2, smoothed.cy - smoothed.h/2,
                                    smoothed.cx + smoothed.w/2, smoothed.cy + smoothed.h/2
                                )
                                boxNorm = RectF(
                                    ltrb.left.coerceIn(0f,1f),
                                    ltrb.top.coerceIn(0f,1f),
                                    ltrb.right.coerceIn(0f,1f),
                                    ltrb.bottom.coerceIn(0f,1f)
                                )

                                val state = controller.update(boxNorm, level, System.currentTimeMillis())
                                zoomCtl?.applyZoomForArea(
                                    area = (boxNorm.width()*boxNorm.height()).coerceIn(1e-5f, 1f),
                                    threshold = 1.5f
                                )
                                if (state.shouldSnap && !locked) {
                                    locked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cameraUse?.let {
                                        takePicture(it.imageCapture) {
                                            showReward = true
                                            scope.launch {
                                                delay(800)
                                                showReward = false
                                                locked = false
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        downscaleLongSide = 320
                    )
                    val use = bindCameraUseCases(context, lifecycleOwner, pv, analyzer)
                    cameraUse = use
                    pv.post { contentRectPx = RectF(0f, 0f, pv.width.toFloat(), pv.height.toFloat()) }
                }
            }
        }
    )

    // 叠加层
    Box(Modifier.fillMaxSize()) {
        val boxPx = remember(boxNorm, contentRectPx) { mapToView(boxNorm, contentRectPx) }
        OverlayBox(
            contentRectPx = contentRectPx,
            boxPx = boxPx,
            showArrow = !locked,
            locked = locked
        )
        DevHud(
            latencyMs = lastLatency,
            feedHz = feedHz,
            inferHz = inferHz,
            shake = level.name,
            flavor = flavor,
            zSuggested = 1f / sqrt(max(1e-5f, boxNorm.width()*boxNorm.height()))
        )
        if (showReward) RewardFlash()
    }
}

private fun takePicture(
    imageCapture: ImageCapture,
    onDone: () -> Unit
) {
    imageCapture.takePicture(
        java.util.concurrent.Executors.newSingleThreadExecutor(),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                image.close(); onDone()
            }
            override fun onError(exception: ImageCaptureException) {
                onDone()
            }
        }
    )
}
