package com.zzsyp.livecompose.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import boofcv.abst.tracker.TrackerObjectQuad
import boofcv.android.ConvertBitmap
import boofcv.alg.tracker.sfot.ConfigSfot
import boofcv.factory.tracker.FactoryTrackerObjectQuad
import boofcv.struct.image.GrayU8
import georegression.struct.shapes.Quadrilateral_F64
import kotlin.math.max
import kotlin.math.min

/**
 * 基于 BoofCV 的 SFOT（稀疏光流目标跟踪）包装：
 * - start() 用模型给出的归一化框初始化跟踪
 * - update() 在新帧上更新位置，返回新的归一化 LTRB 框；丢失则返回 null
 *
 * 依赖：
 *   implementation("org.boofcv:boofcv-android:1.2.3")
 *   implementation("org.boofcv:boofcv-all:1.2.3")
 */
class BoofSfotTracker {

    private val gray = GrayU8(1, 1)
    private var work: ByteArray? = null
    private var tracker: TrackerObjectQuad<GrayU8>? = null
    private var imgW = 0
    private var imgH = 0
    private var lastQuad: Quadrilateral_F64? = null

    private fun ensureBuffer(bmp: Bitmap) {
        if (gray.width != bmp.width || gray.height != bmp.height) {
            gray.reshape(bmp.width, bmp.height)
            work = ConvertBitmap.declareStorage(bmp, work)
            imgW = bmp.width
            imgH = bmp.height
        }
        ConvertBitmap.bitmapToGray(bmp, gray, work)
    }

    fun reset() {
        tracker = null
        lastQuad = null
    }

    fun start(bmp: Bitmap, normBox: RectF) {
        ensureBuffer(bmp)

        // 归一化 LTRB -> 像素四边形（顺时针 a,b,c,d）
        val l = (normBox.left * imgW).toDouble()
        val t = (normBox.top * imgH).toDouble()
        val r = (normBox.right * imgW).toDouble()
        val b = (normBox.bottom * imgH).toDouble()
        val quad = Quadrilateral_F64(l, t, r, t, r, b, l, b)

        val cfg = ConfigSfot() // 用默认配置即可，速度/鲁棒性平衡佳
        tracker = FactoryTrackerObjectQuad.sparseFlow(cfg, GrayU8::class.java, null)
        val ok = tracker!!.initialize(gray, quad)
        lastQuad = if (ok) quad else null
    }

    fun update(bmp: Bitmap): RectF? {
        val t = tracker ?: return null
        ensureBuffer(bmp)

        val out = Quadrilateral_F64()
        val ok = t.process(gray, out)
        if (!ok) return null

        lastQuad = out

        // 四边形 -> 外接轴对齐框 -> 归一化
        val minX = min(min(out.a.x, out.b.x), min(out.c.x, out.d.x))
        val maxX = max(max(out.a.x, out.b.x), max(out.c.x, out.d.x))
        val minY = min(min(out.a.y, out.b.y), min(out.c.y, out.d.y))
        val maxY = max(max(out.a.y, out.b.y), max(out.c.y, out.d.y))

        return RectF(
            (minX / imgW).toFloat().coerceIn(0f, 1f),
            (minY / imgH).toFloat().coerceIn(0f, 1f),
            (maxX / imgW).toFloat().coerceIn(0f, 1f),
            (maxY / imgH).toFloat().coerceIn(0f, 1f),
        )
    }
}
