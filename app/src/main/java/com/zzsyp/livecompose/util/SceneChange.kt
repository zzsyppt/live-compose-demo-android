package com.zzsyp.livecompose.util

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 将图像快速下采样到 32x32 灰度，并返回 signature（长度=1024）的 float[]。
 */
fun signature32x32(bmp: Bitmap): FloatArray {
    val w = 32; val h = 32
    val small = Bitmap.createScaledBitmap(bmp, w, h, true)
    val sig = FloatArray(w*h)
    var i = 0
    val pixels = IntArray(w*h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = (p) and 0xFF
        sig[i++] = (0.299f*r + 0.587f*g + 0.114f*b) / 255f
    }
    if (small != bmp) small.recycle()
    return sig
}

/** 计算两次 signature 的平均绝对差 */
fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
    val n = a.size
    var s = 0f
    for (i in 0 until n) s += abs(a[i] - b[i])
    return s / n
}
