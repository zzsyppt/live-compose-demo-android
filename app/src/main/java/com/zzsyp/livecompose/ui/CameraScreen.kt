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
import com.zzsyp.livecompose.guidance.ShakeLevel
import com.zzsyp.livecompose.guidance.RectNorm
import com.zzsyp.livecompose.ml.CropModel
import com.zzsyp.livecompose.ml.CropResult
import com.zzsyp.livecompose.ml.ModelProvider
import com.zzsyp.livecompose.util.RateLimiter
import com.zzsyp.livecompose.util.mapToView
import com.zzsyp.livecompose.util.createOutputOptions
import com.zzsyp.livecompose.util.generateName
import com.zzsyp.livecompose.util.meanAbsDiff
import com.zzsyp.livecompose.util.signature32x32
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.sqrt
import com.zzsyp.livecompose.util.ShakeDetector
import androidx.compose.runtime.DisposableEffect


/**
 * 稳定→(只)取样一次→proposalBox 引导→先平滑变焦→再拍照（只保存原图）
 */
@Composable
fun CameraScreen(flavor: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // —— 采样状态机 —— //
    var proposalBox by remember { mutableStateOf<RectF?>(null) } // 模型产出的建议框（只在稳定时取样一次）
    var sampling by remember { mutableStateOf(false) }            // 正在跑一次模型
    var stableSince by remember { mutableStateOf<Long?>(null) }   // 进入 Stable 的时间戳
    var lastSig by remember { mutableStateOf<FloatArray?>(null) } // 上次送模帧的签名
    val HOLD_MS = 250L                                           // 需要保持稳定的时长
    val CHANGE_TH = 0.12f                                        // 场景变化阈值（慢移动也能触发重取样）

    // —— 抖动检测 —— //
    var level by remember { mutableStateOf(ShakeLevel.Stable) }
    DisposableEffect(Unit) {
        val det = ShakeDetector(context) { level = it }
        det.start()
        onDispose { det.stop() }
    }
    // 在抖动等级变化时维护 stableSince（进入 Stable 开始记时；离开 Stable 清空）
    LaunchedEffect(level) {
        if (level == ShakeLevel.Stable) {
            if (stableSince == null) stableSince = System.currentTimeMillis()
        } else {
            stableSince = null
        }
    }

    // —— CameraX 状态 —— //
    var cameraUse by remember { mutableStateOf<CameraUseCases?>(null) }
    var contentRectPx by remember { mutableStateOf(RectF()) }

    // —— 模型 —— //
    val model: CropModel = remember { ModelProvider.provide() }

    // —— 引导与变焦控制 —— //
    val ema = remember { EmaBox(alpha = 0.6f) }
    val controller = remember { GuidanceController() }
    val zoomCtl = remember(cameraUse) { cameraUse?.let { ZoomControl(it.camera, scope) } }

    // —— 统计 —— //
    var lastLatency by remember { mutableLongStateOf(0L) }
    var feedHz by remember { mutableFloatStateOf(0f) }
    var inferHz by remember { mutableFloatStateOf(0f) }
    var lastFeedNs by remember { mutableLongStateOf(0L) }
    var lastInferNs by remember { mutableLongStateOf(0L) }

    // —— UI 状态 —— //
    var boxNorm by remember { mutableStateOf(RectF(0.2f, 0.2f, 0.8f, 0.8f)) }
    var locked by remember { mutableStateOf(false) }
    var showReward by remember { mutableStateOf(false) }

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
    LaunchedEffect(Unit) {
        if (!grantedCamera) reqCamera.launch(Manifest.permission.CAMERA)
    }

    // —— 预览视图与 CameraX 绑定（仅在授权后） —— //
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PreviewView(it).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                // 用 TextureView，兼容部分机型的 SurfaceView 黑屏
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { pv ->
            if (grantedCamera && cameraUse == null) {
                scope.launch {
                    val analyzer = FrameAnalyzer(
                        onFrame = { bmp: Bitmap ->
                            // 1) 统计 feedHz
                            val now = System.nanoTime()
                            if (lastFeedNs > 0) feedHz = 1e9f / (now - lastFeedNs)
                            lastFeedNs = now

                            // 2) 仅在稳定一段时间后才考虑“取样一次”；且避免并发取样
                            val nowMs = System.currentTimeMillis()
                            val isStable = stableSince?.let { nowMs - it >= HOLD_MS } == true
                            if (!isStable || sampling) return@FrameAnalyzer

                            // 3) 场景变化检测（解决慢速移动但未被判“抖动”的情况）
                            val sig = signature32x32(bmp)
                            val sceneChanged = lastSig?.let { meanAbsDiff(it, sig) >= CHANGE_TH } ?: true

                            // 4) 两种情况允许“取样一次”：还没建议框（首次），或场景明显变化（再次）
                            val shouldSample = proposalBox == null || sceneChanged
                            if (!shouldSample) return@FrameAnalyzer

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
                                val ltrb = RectF(
                                    (smoothed.cx - smoothed.w / 2f).coerceIn(0f, 1f),
                                    (smoothed.cy - smoothed.h / 2f).coerceIn(0f, 1f),
                                    (smoothed.cx + smoothed.w / 2f).coerceIn(0f, 1f),
                                    (smoothed.cy + smoothed.h / 2f).coerceIn(0f, 1f)
                                )
                                proposalBox = ltrb
                                lastSig = sig
                                sampling = false
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

    // —— 叠加层与引导（由 proposalBox 驱动） —— //
    Box(Modifier.fillMaxSize()) {
        proposalBox?.let { pbox ->
            // 用取样得到的建议框驱动 UI 与引导
            boxNorm = pbox

            val state = controller.update(boxNorm, level, System.currentTimeMillis())

            // 对齐中心后：先平滑变焦，再自动拍照（仅保存原图）
            if (state.shouldSnap && !locked) {
                locked = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val zTarget = state.zSuggested
                cameraUse?.let { use ->
                    scope.launch {
                        // 用户可见的变焦动画
                        zoomCtl?.rampToAndWait(zTarget.coerceAtMost(3.0f), steps = 15, stepDelayMs = 16L)
                        // 变焦完成 → 拍照（仅保存原图）
                        takeAndSaveJpeg(
                            context = context,
                            imageCapture = use.imageCapture
                        ) {
                            showReward = true
                            scope.launch {
                                delay(800)
                                showReward = false
                                locked = false
                                // 拍照后清空建议框，等待下一次稳定 → 再取样
                                proposalBox = null
                                lastSig = null
                                stableSince = null
                            }
                        }
                    }
                }
            }
        }

        // 画框/中心圈/箭头
        val boxPx = remember(boxNorm, contentRectPx) { mapToView(boxNorm, contentRectPx) }
        OverlayBox(
            contentRectPx = contentRectPx,
            boxPx = boxPx,
            showArrow = !locked,
            locked = locked
        )

        // 开发者 HUD
        DevHud(
            latencyMs = lastLatency,
            feedHz = feedHz,
            inferHz = inferHz,
            shake = level.name,
            flavor = flavor,
            zSuggested = 1f / sqrt(max(1e-5f, boxNorm.width() * boxNorm.height()))
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
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onDone()
            }
            override fun onError(exception: ImageCaptureException) {
                onDone()
            }
        }
    )
}
