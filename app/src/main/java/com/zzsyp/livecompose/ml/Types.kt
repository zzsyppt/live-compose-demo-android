package com.zzsyp.livecompose.ml

data class CropResult(
    val cx: Float, val cy: Float, val w: Float, val h: Float, // 全部 ∈ [0,1]
    val score: Float = 0f,
    val latencyMs: Long = 0L
)