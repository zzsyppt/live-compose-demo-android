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
        val b = p and 0xFF
        sig[i++] = (0.299f*r + 0.587f*g + 0.114f*b) / 255f
    }
    if (small != bmp) small.recycle()
    return sig
}

/** 计算两次 signature 的平均绝对差（场景变化度 0~1） */
fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
    val n = a.size
    var s = 0f
    for (i in 0 until n) s += abs(a[i] - b[i])
    return s / n
}

/**
 * 基于 32x32 灰度签名的整数位移估计（全局平移模型）。
 * 在 (-search,+search) 像素窗口内寻找使 SAD 最小的 (dx,dy)。
 * 返回 (dx,dy) 的含义：将 **curr** 向 (dx,dy) 位移后最像 **prev**。
 */
fun estimateShiftFrom32(prev: FloatArray, curr: FloatArray, search: Int = 3): Pair<Int, Int> {
    val w = 32; val h = 32
    var best = Float.POSITIVE_INFINITY
    var bestDx = 0; var bestDy = 0
    for (dy in -search..search) {
        for (dx in -search..search) {
            var sad = 0f
            for (y in 0 until h) {
                val yy = y + dy
                if (yy !in 0 until h) continue
                var base = y * w
                var base2 = yy * w
                for (x in 0 until w) {
                    val xx = x + dx
                    if (xx !in 0 until w) continue
                    val a = prev[base + x]
                    val b = curr[base2 + xx]
                    sad += abs(a - b)
                }
            }
            if (sad < best) {
                best = sad
                bestDx = dx
                bestDy = dy
            }
        }
    }
    return bestDx to bestDy
}
