package com.zzsyp.livecompose.camera

import android.graphics.*
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 兼容两路：
 * - RGBA_8888：直接拷贝
 * - YUV_420_888：先转 NV21，再用 YuvImage->JPEG 解码为 Bitmap（便于 Demo 先跑通）
 */
class FrameAnalyzer(
    private val onFrame: (Bitmap) -> Unit,
    private val downscaleLongSide: Int = 320
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val bmp = when (image.format) {
                PixelFormat.RGBA_8888, ImageFormat.FLEX_RGBA_8888 -> rgbaToBitmap(image)
                ImageFormat.YUV_420_888 -> yuvToBitmap(image)
                else -> null
            } ?: return

            val w = bmp.width
            val h = bmp.height
            val scale = downscaleLongSide / max(w, h).toFloat()
            val dstW = max(1, (w * scale).roundToInt())
            val dstH = max(1, (h * scale).roundToInt())
            val small = Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
            if (small != bmp) bmp.recycle()

            onFrame(small)
        } catch (_: Throwable) {
            // 忽略本帧异常
        } finally {
            image.close()
        }
    }

    private fun rgbaToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes.first()
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // 应为 4
        val buffer: ByteBuffer = plane.buffer

        val rowBuf = ByteArray(rowStride * height)
        buffer.get(rowBuf)
        val tight = ByteArray(width * height * 4)
        var src = 0
        var dst = 0
        for (y in 0 until height) {
            System.arraycopy(rowBuf, src, tight, dst, width * pixelStride)
            src += rowStride
            dst += width * pixelStride
        }

        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(ByteBuffer.wrap(tight))
        return full
    }

    private fun yuvToBitmap(image: ImageProxy): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Y
        val y = image.planes[0]
        y.buffer.get(nv21, 0, y.buffer.remaining())

        // U、V
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val uBytes = ByteArray(uBuffer.capacity()).also { uBuffer.get(it) }
        val vBytes = ByteArray(vBuffer.capacity()).also { vBuffer.get(it) }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var offset = ySize

        var uRow = 0
        var vRow = 0
        for (row in 0 until chromaHeight) {
            var uCol = 0
            var vCol = 0
            for (col in 0 until chromaWidth) {
                val vIndex = vRow + vCol
                val uIndex = uRow + uCol
                nv21[offset++] = vBytes[vIndex]
                nv21[offset++] = uBytes[uIndex]
                uCol += u.pixelStride
                vCol += v.pixelStride
            }
            uRow += u.rowStride
            vRow += v.rowStride
        }
        return nv21
    }
}
