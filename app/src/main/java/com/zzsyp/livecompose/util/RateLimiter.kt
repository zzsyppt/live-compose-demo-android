package com.zzsyp.livecompose.util

class RateLimiter(private val maxHz: Float) {
    private var lastNs = 0L
    fun tryPass(nowNs: Long = System.nanoTime()): Boolean {
        val intervalNs = (1e9 / maxHz).toLong()
        if (nowNs - lastNs >= intervalNs) {
            lastNs = nowNs
            return true
        }
        return false
    }
}
