package com.zzsyp.livecompose.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.zzsyp.livecompose.guidance.ShakeLevel
import kotlin.math.sqrt

/**
 * 简易抖动检测：基于加速度模的高通分量估计
 * 注意：为 Demo 目的，参数偏保守；生产可融合陀螺或光流。
 */
class ShakeDetector(
    context: Context,
    private val onLevel: (ShakeLevel) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var gravity = 9.81f
    private var ema = 0f
    private val alpha = 0.1f // EMA 系数

    fun start() {
        if (running || sensor == null) return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        running = true
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0]
        val ay = e.values[1]
        val az = e.values[2]
        val a = sqrt(ax*ax + ay*ay + az*az)
        val high = kotlin.math.abs(a - gravity)
        ema = ema * (1 - alpha) + high * alpha

        val level = when {
            ema < 0.15f -> ShakeLevel.Stable
            ema < 0.35f -> ShakeLevel.Wobbly
            else        -> ShakeLevel.Shaky
        }
        onLevel(level)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
