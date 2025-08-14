package com.zzsyp.livecompose.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zzsyp.livecompose.camera.*
import com.zzsyp.livecompose.guidance.EmaBox
import com.zzsyp.livecompose.guidance.GuidanceController
import com.zzsyp.livecompose.guidance.RectNorm
import com.zzsyp.livecompose.guidance.ShakeLevel
import com.zzsyp.livecompose.ml.CropModel
import com.zzsyp.livecompose.ml.CropResult
import com.zzsyp.livecompose.ml.ModelProvider
import com.zzsyp.livecompose.tracking.BoofSfotTracker
import com.zzsyp.livecompose.util.*
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class FlowPhase { IDLE, PROPOSED, ALIGNING, ZOOMING, CAPTURING }

@Composable
fun CameraScreen(flavor: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // 抖动 & 稳定窗
    var level by remember { mutableStateOf(ShakeLevel.Stable) }
    var stableSince by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        val det = ShakeDetector(context) { level = it }
        det.start(); onDispose { det.stop() }
    }
    LaunchedEffect(level) {
        if (level == ShakeLevel.Stable) {
            if (stableSince == null) stableSince = System.currentTimeMillis()
        } else stableSince = null
    }

    // CameraX
    var cameraUse by remember { mutableStateOf<CameraUseCases?>(null) }
    var contentRectPx by remember { mutableStateOf(RectF()) }
    var baseZoom by remember { mutableStateOf(1.0f) }
    val zoomCtl = remember(cameraUse) { cameraUse?.let { ZoomControl(it.camera, scope) } }

    // 模型 & 控制器
    val model: CropModel = remember { ModelProvider.provide() }
    val ema = remember { EmaBox(alpha = 0.6f) }
    val controller = remember { GuidanceController() }
    val rate = remember { RateLimiter(maxHz = 15f) }
    val boofTracker = remember { BoofSfotTracker() } // ★ 改用 BoofCV

    // 统计
    var lastLatency by remember { mutableLongStateOf(0L) }
    var feedHz by remember { mutableFloatStateOf(0f) }
    var inferHz by remember { mutableFloatStateOf(0f) }
    var lastFeedNs by remember { mutableLongStateOf(0L) }
    var lastInferNs by remember { mutableLongStateOf(0L) }

    // 交互状态
    var phase by remember { mutableStateOf(FlowPhase.IDLE) }
    var boxNorm by remember { mutableStateOf(RectF(0.4f, 0.4f, 0.6f, 0.6f)) }
    var proposalBox by remember { mutableStateOf<RectF?>(null) }
    var sampling by remember { mutableStateOf(false) }
    var showReward by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }

    // 进度保护（防止用户“对的动作”被重采样打断）
    var bestErr by remember { mutableStateOf(1f) }
    var lastProgressTs by remember { mutableStateOf(0L) }
    val progressGraceMs = 1500L

    // 调试：送模次数
    var sampleCount by remember { mutableStateOf(0) }

    // 权限（仅 CAMERA）
    var grantedCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
    }
    val reqCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        grantedCamera = ok
    }
    LaunchedEffect(Unit) { if (!grantedCamera) reqCamera.launch(Manifest.permission.CAMERA) }

    // 预览 + 绑定
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { pv ->
            if (grantedCamera && cameraUse == null) {
                scope.launch {
                    val analyzer = FrameAnalyzer(
                        onFrame = { bmp: Bitmap ->
                            val now = System.nanoTime()
                            if (lastFeedNs > 0) feedHz = 1e9f / (now - lastFeedNs)
                            lastFeedNs = now
                            if (!rate.tryPass(now)) return@FrameAnalyzer
                            val nowMs = System.currentTimeMillis()

                            // A) 有 proposal：用 BoofCV 粘滞跟随 + 进度保护 + 对齐阶段
                            proposalBox?.let {
                                boofTracker.update(bmp)?.let { tracked -> proposalBox = tracked }

                                val p = proposalBox!!
                                val cx = (p.left + p.right) * 0.5f
                                val cy = (p.top + p.bottom) * 0.5f
                                val err = sqrt((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f))
                                if (err <= bestErr * 1.02f) {
                                    bestErr = min(bestErr, err)
                                    lastProgressTs = nowMs
                                }
                                val allowReacquire = (nowMs - lastProgressTs > progressGraceMs && err > bestErr * 1.6f)
                                if (allowReacquire) {
                                    proposalBox = null
                                    boofTracker.reset()
                                    bestErr = 1f
                                    phase = FlowPhase.IDLE
                                }
                                if (phase == FlowPhase.PROPOSED) phase = FlowPhase.ALIGNING
                            }

                            // B) 无 proposal：仅在稳定窗口内触发一次取样
                            val isStable = stableSince?.let { nowMs - it >= 250L } == true
                            if (proposalBox == null && !sampling && isStable) {
                                sampling = true
                                val frameCopy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                scope.launch(Dispatchers.Default) {
                                    val t0 = System.nanoTime()
                                    val res: CropResult = model.predict(frameCopy)
                                    lastLatency = (System.nanoTime() - t0) / 1_000_000
                                    val now2 = System.nanoTime()
                                    if (lastInferNs > 0) inferHz = 1e9f / (now2 - lastInferNs)
                                    lastInferNs = now2

                                    val s = ema.smooth(RectNorm(res.cx, res.cy, res.w, res.h))
                                    val l = (s.cx - s.w / 2f).coerceIn(0f, 1f)
                                    val t = (s.cy - s.h / 2f).coerceIn(0f, 1f)
                                    val r = (s.cx + s.w / 2f).coerceIn(0f, 1f)
                                    val b = (s.cy + s.h / 2f).coerceIn(0f, 1f)
                                    val rect = RectF(l, t, r, b)

                                    // ★ 用 BoofCV 初始化跟踪
                                    boofTracker.start(frameCopy, rect)

                                    withContext(Dispatchers.Main) {
                                        proposalBox = rect
                                        bestErr = 1f
                                        lastProgressTs = nowMs
                                        phase = FlowPhase.PROPOSED
                                        sampleCount += 1
                                    }
                                    sampling = false
                                }
                            }
                        },
                        downscaleLongSide = 320
                    )
                    val use = bindCameraUseCases(context, lifecycleOwner, pv, analyzer)
                    cameraUse = use
                    baseZoom = use.camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f

                    pv.post {
                        contentRectPx = RectF(0f, 0f, pv.width.toFloat(), pv.height.toFloat())
                    }
                }
            }
        }
    )

    // 叠加层：变焦/拍照阶段隐藏框
    Box(Modifier.fillMaxSize()) {
        val showBox = proposalBox != null && (phase != FlowPhase.ZOOMING && phase != FlowPhase.CAPTURING)
        if (showBox) boxNorm = proposalBox!!

        val boxPx = remember(boxNorm, contentRectPx) { mapToView(boxNorm, contentRectPx) }
        OverlayBox(
            contentRectPx = contentRectPx,
            boxPx = boxPx,
            showArrow = showBox && !locked,
            locked = locked
        )

        // 引导 → 变焦 → 拍照 → 复位
        proposalBox?.let { pbox ->
            val state = controller.update(pbox, level, System.currentTimeMillis())
            if (state.shouldSnap && !locked && phase != FlowPhase.ZOOMING && phase != FlowPhase.CAPTURING) {
                locked = true
                LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)
                val zTarget = state.zSuggested.coerceAtMost(3.0f)
                cameraUse?.let { use ->
                    scope.launch {
                        phase = FlowPhase.ZOOMING
                        zoomCtl?.rampToAndWait(zTarget, steps = 15, stepDelayMs = 16L)

                        phase = FlowPhase.CAPTURING
                        takeAndSaveJpeg(context, use.imageCapture) {
                            // 奖励动效 + 清理 & 复位
                            var show by remember { mutableStateOf(false) }
                            showReward = true
                            scope.launch {
                                delay(800)
                                showReward = false
                                locked = false
                                proposalBox = null
                                boofTracker.reset()
                                bestErr = 1f
                                phase = FlowPhase.IDLE
                                zoomCtl?.resetTo(baseZoom, steps = 15, stepDelayMs = 16L)
                            }
                        }
                    }
                }
            }
        }

        // HUD + 送模计数
        DevHud(
            latencyMs = lastLatency,
            feedHz = feedHz,
            inferHz = inferHz,
            shake = level.name,
            flavor = flavor,
            zSuggested = 1f / sqrt(max(1e-5f, boxNorm.width() * boxNorm.height()))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) { Text("samples: $sampleCount", color = Color.White) }

        if (showReward) RewardFlash()
    }
}

private fun takeAndSaveJpeg(
    context: Context,
    imageCapture: ImageCapture,
    onDone: () -> Unit
) {
    val name = generateName("LiveCompose")
    val output = createOutputOptions(context, name)
    val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    imageCapture.takePicture(
        output, exec,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onDone()
            override fun onError(exception: ImageCaptureException) = onDone()
        }
    )
}
