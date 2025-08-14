package com.zzsyp.livecompose.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 将 RGBA_8888 的 ImageProxy 转为下采样后的 Bitmap（用于喂“假模型”）
 * 处理了 rowStride 可能大于 width*4 的情况（逐行拷贝去掉 padding）。
 */
class FrameAnalyzer(
    private val onFrame: (Bitmap) -> Unit,
    private val downscaleLongSide: Int = 320 // 模型输入长边
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val format = image.format
            if (!(format == PixelFormat.RGBA_8888 || format == ImageFormat.FLEX_RGBA_8888)) {
                image.close(); return
            }
            val plane = image.planes.firstOrNull() ?: run { image.close(); return }
            val width = image.width
            val height = image.height
            val buffer: ByteBuffer = plane.buffer

            val rowStride = plane.rowStride     // bytes per row in buffer
            val pixelStride = plane.pixelStride // expected 4
            if (pixelStride != 4) {
                // 非 4 字节像素（极少见），放弃本帧
                image.close(); return
            }

            // 将包含 padding 的源缓冲区按行裁掉 padding，拼成紧凑的 RGBA 数组
            val rowBuf = ByteArray(rowStride * height)
            buffer.get(rowBuf)
            val tight = ByteArray(width * height * 4)
            var src = 0
            var dst = 0
            for (y in 0 until height) {
                System.arraycopy(rowBuf, src, tight, dst, width * 4)
                src += rowStride
                dst += width * 4
            }

            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(ByteBuffer.wrap(tight))

            // 按长边缩放到 downscaleLongSide
            val scale = downscaleLongSide / max(width, height).toFloat()
            val dstW = max(1, (width * scale).roundToInt())
            val dstH = max(1, (height * scale).roundToInt())
            val small = Bitmap.createScaledBitmap(full, dstW, dstH, true)
            full.recycle()

            onFrame(small)
        } catch (_: Throwable) {
            // 忽略单帧异常，释放资源
        } finally {
            image.close()
        }
    }
}
