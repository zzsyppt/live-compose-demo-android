package com.zzsyp.livecompose.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 *  Jetpack Compose UI 组件，主要功能是在界面上绘制叠加层（Overlay），
 *  用于显示特定区域的框选效果、中心标记和引导箭头
 */
@Composable
fun OverlayBox(
    contentRectPx: RectF,
    boxPx: RectF,
    showArrow: Boolean,
    locked: Boolean
) {
    Canvas(Modifier.fillMaxSize()) {
        // 建议框
        drawRect(
            color = if (locked) Color(0xFF4CAF50) else Color.White,
            topLeft = Offset(boxPx.left, boxPx.top),
            size = androidx.compose.ui.geometry.Size(boxPx.width(), boxPx.height()),
            style = Stroke(width = 6f, pathEffect = PathEffect.cornerPathEffect(20f))
        )

        // 屏幕中心圈
        val c = Offset(size.width/2f, size.height/2f)
        drawCircle(
            color = Color.White.copy(alpha = 0.4f),
            radius = 48f, center = c,
            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
        )

        // 箭头（中心→框中心）
        if (showArrow) {
            val cx = boxPx.centerX()
            val cy = boxPx.centerY()
            drawLine(
                color = Color.Yellow,
                start = c, end = Offset(cx, cy),
                strokeWidth = 8f
            )
        }
    }
}
