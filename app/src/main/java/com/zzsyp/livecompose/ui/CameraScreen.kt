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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
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

    // —— 抖动检测与稳定时间 —— //
    var level by remember { mutableStateOf(ShakeLevel.Stable) }
    var stableSince by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        val det = com.zzsyp.livecompose.util.ShakeDetector(context) { level = it }
        det.start()
        onDispose { det.stop() }
    }
    LaunchedEffect(level) {
        if (level == ShakeLevel.Stable) {
            if (stableSince == null) stableSince = System.currentTimeMillis()
        } else stableSince = null
    }

    // —— CameraX —— //
    var cameraUse by remember { mutableStateOf<CameraUseCases?>(null) }
    var contentRectPx by remember { mutableStateOf(RectF()) }
    var baseZoom by remember { mutableStateOf(1.0f) }

    // —— 模型与引导 —— //
    val model: CropModel = remember { ModelProvider.provide() }
    val ema = remember { EmaBox(alpha = 0.6f) }
    val controller = remember { GuidanceController() }
    val rate = remember { RateLimiter(maxHz = 15f) }
    val zoomCtl = remember(cameraUse) { cameraUse?.let { ZoomControl(it.camera, scope) } }

    // —— 统计 —— //
    var lastLatency by remember { mutableLongStateOf(0L) }
    var feedHz by remember { mutableFloatStateOf(0f) }
    var inferHz by remember { mutableFloatStateOf(0f) }
    var lastFeedNs by remember { mutableLongStateOf(0L) }
    var lastInferNs by remember { mutableLongStateOf(0L) }

    // —— 交互状态 —— //
    var phase by remember { mutableStateOf(FlowPhase.IDLE) }
    var boxNorm by remember { mutableStateOf(RectF(0.4f,0.4f,0.6f,0.6f)) } // 仅用于绘制/HUD
    var proposalBox by remember { mutableStateOf<RectF?>(null) }            // 取样产生的框
    var sampling by remember { mutableStateOf(false) }
    var showReward by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) } // 保持原语义：进入快门阶段短暂锁定

    // —— 进度保护（防止“正确移动”被误判为场景变化而重采样） —— //
    var bestErr by remember { mutableStateOf(1f) }                  // 当前轮对齐过程的最小误差
    var lastProgressTs by remember { mutableStateOf(0L) }           // 最近一次“误差有改善”的时间
    val progressGraceMs = 1500L                                     // 这段时间内禁止重采样
    val lostFactor = 1.6f                                           // 误差恶化到最优的多少倍才判丢失
    val reacquireChangeTh = 0.25f                                   // 场景变化很大时的兜底重采样阈值

    // —— 粘滞跟随（全局位移） —— //
    var lastSig by remember { mutableStateOf<FloatArray?>(null) }

    // —— 权限（仅 CAMERA） —— //
    var grantedCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val reqCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        grantedCamera = ok
    }
    LaunchedEffect(Unit) { if (!grantedCamera) reqCamera.launch(Manifest.permission.CAMERA) }

    // —— 预览 + 绑定 —— //
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
                            // 统计 feedHz
                            val now = System.nanoTime()
                            if (lastFeedNs > 0) feedHz = 1e9f / (now - lastFeedNs)
                            lastFeedNs = now
                            if (!rate.tryPass(now)) return@FrameAnalyzer

                            val nowMs = System.currentTimeMillis()

                            // 1) 若已有 proposal，做“粘滞跟随” + 对齐进度保护
                            proposalBox?.let { pbox0 ->
                                // 粘滞跟随：估计全局位移，把框随场景移动
                                val sig = signature32x32(bmp)
                                lastSig?.let { prevSig ->
                                    val (dx, dy) = estimateShiftFrom32(prevSig, sig, search = 3)
                                    // curr 向 (dx,dy) 移后最像 prev → 场景相对屏幕位移约为 (-dx,-dy)
                                    val sx = -dx / 32f
                                    val sy = -dy / 32f
                                    val moved = RectF(
                                        (pbox0.left + sx).coerceIn(0f,1f),
                                        (pbox0.top + sy).coerceIn(0f,1f),
                                        (pbox0.right + sx).coerceIn(0f,1f),
                                        (pbox0.bottom + sy).coerceIn(0f,1f)
                                    )
                                    proposalBox = moved
                                }
                                lastSig = sig

                                // 误差（框中心→屏幕中心）
                                val cx = (proposalBox!!.left + proposalBox!!.right)/2f
                                val cy = (proposalBox!!.top + proposalBox!!.bottom)/2f
                                val err = sqrt((cx-0.5f)*(cx-0.5f) + (cy-0.5f)*(cy-0.5f))

                                // 更新进度：若误差有改善，则刷新“保护时间窗”
                                if (err <= bestErr * 1.02f) {
                                    bestErr = min(bestErr, err)
                                    lastProgressTs = nowMs
                                }

                                // 在进度保护窗内，禁止重采样
                                // 若误差恶化严重或场景变化很大 → 允许重采样
                                val allowReacquire =
                                    (nowMs - lastProgressTs > progressGraceMs && err > bestErr * lostFactor) ||
                                            (lastSig != null && meanAbsDiff(lastSig!!, sig) >= reacquireChangeTh)

                                // 进入引导阶段
                                if (phase == FlowPhase.PROPOSED) phase = FlowPhase.ALIGNING

                                // 只有在允许重采样时，才把 proposal 清空，后续再触发取样
                                if (allowReacquire) {
                                    proposalBox = null
                                    lastSig = null
                                    bestErr = 1f
                                }
                            }

                            // 2) 采样：只有在 Stable 且没有 proposal 时才取样一次
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

                                    val smoothed = ema.smooth(RectNorm(res.cx, res.cy, res.w, res.h))
                                    val l = (smoothed.cx - smoothed.w/2f).coerceIn(0f,1f)
                                    val t2 = (smoothed.cy - smoothed.h/2f).coerceIn(0f,1f)
                                    val r = (smoothed.cx + smoothed.w/2f).coerceIn(0f,1f)
                                    val b = (smoothed.cy + smoothed.h/2f).coerceIn(0f,1f)
                                    proposalBox = RectF(l, t2, r, b)
                                    bestErr = 1f
                                    lastProgressTs = nowMs
                                    phase = FlowPhase.PROPOSED
                                    sampling = false
                                }
                            }
                        },
                        downscaleLongSide = 320
                    )
                    val use = bindCameraUseCases(context, lifecycleOwner, pv, analyzer)
                    cameraUse = use
                    // 记录基准变焦（通常为 1.0x）
                    baseZoom = zoomCtl?.currentZoom() ?: 1.0f
                    pv.post { contentRectPx = RectF(0f, 0f, pv.width.toFloat(), pv.height.toFloat()) }
                }
            }
        }
    )

    // —— 叠加层：由 proposalBox 驱动；变焦/拍照时隐藏框 —— //
    Box(Modifier.fillMaxSize()) {
        // 根据状态决定是否显示框（ZOOMING/CAPTURING 隐藏）
        val showBoxNow = proposalBox != null && (phase != FlowPhase.ZOOMING && phase != FlowPhase.CAPTURING)

        if (showBoxNow) {
            boxNorm = proposalBox!!
        }

        val boxPx = remember(boxNorm, contentRectPx) { mapToView(boxNorm, contentRectPx) }
        OverlayBox(
            contentRectPx = contentRectPx,
            boxPx = boxPx,
            showArrow = showBoxNow && !locked,
            locked = locked
        )

        // 引导与拍摄：只在有 proposal 时运行
        proposalBox?.let { pbox ->
            val state = controller.update(pbox, level, System.currentTimeMillis())
            // 对齐后：先进入 ZOOMING → 完成后 CAPTURING → 回到 IDLE
            if (state.shouldSnap && !locked && phase != FlowPhase.ZOOMING && phase != FlowPhase.CAPTURING) {
                locked = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val zTarget = state.zSuggested.coerceAtMost(3.0f)
                cameraUse?.let { use ->
                    scope.launch {
                        // 1) 变焦阶段：隐藏框
                        phase = FlowPhase.ZOOMING
                        zoomCtl?.rampToAndWait(zTarget, steps = 15, stepDelayMs = 16L)

                        // 2) 拍照阶段
                        phase = FlowPhase.CAPTURING
                        takeAndSaveJpeg(
                            context = context,
                            imageCapture = use.imageCapture
                        ) {
                            showReward = true
                            scope.launch {
                                delay(800)
                                showReward = false
                                locked = false

                                // 3) 复位：清状态 + 复位变焦 + 回到 IDLE
                                proposalBox = null
                                lastSig = null
                                bestErr = 1f
                                phase = FlowPhase.IDLE
                                zoomCtl?.resetTo(baseZoom, steps = 15, stepDelayMs = 16L)
                            }
                        }
                    }
                }
            }
        }

        // HUD
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

/** 仅保存原图到相册（MediaStore） */
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
