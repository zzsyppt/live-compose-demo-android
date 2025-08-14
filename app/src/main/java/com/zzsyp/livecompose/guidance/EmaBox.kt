package com.zzsyp.livecompose.guidance

data class RectNorm(var cx: Float, var cy: Float, var w: Float, var h: Float)

class EmaBox(private val alpha: Float = 0.6f) {
    private var last: RectNorm? = null
    fun smooth(now: RectNorm): RectNorm {
        val l = last ?: run { last = now; return now }
        val out = RectNorm(
            l.cx * alpha + now.cx * (1 - alpha),
            l.cy * alpha + now.cy * (1 - alpha),
            l.w  * alpha + now.w  * (1 - alpha),
            l.h  * alpha + now.h  * (1 - alpha),
        )
        last = out
        return out
    }
}
