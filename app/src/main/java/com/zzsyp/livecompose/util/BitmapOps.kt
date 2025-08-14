package com.zzsyp.livecompose.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * 位图裁剪（使用归一化 LTRB）
 */
/** 将归一化 LTRB 映射到给定位图的像素 Rect，边界内取整 */
fun normalizedToPixelRect(norm: RectF, bmpWidth: Int, bmpHeight: Int): Rect {
    val l = (norm.left * bmpWidth).coerceIn(0f, bmpWidth - 1f)
    val t = (norm.top * bmpHeight).coerceIn(0f, bmpHeight - 1f)
    val r = (norm.right * bmpWidth).coerceIn(l + 1f, bmpWidth.toFloat())
    val b = (norm.bottom * bmpHeight).coerceIn(t + 1f, bmpHeight.toFloat())
    return Rect(l.roundToInt(), t.roundToInt(), r.roundToInt(), b.roundToInt())
}

/** 按归一化 LTRB 从位图裁剪（返回新位图） */
fun cropBitmapNormalized(src: Bitmap, norm: RectF): Bitmap {
    val rect = normalizedToPixelRect(norm, src.width, src.height)
    return Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
}

/** 使用 ImageDecoder 解码（Android 9+ 可用，推荐 10+），限制长边避免 OOM */
fun decodeBitmapScaled(resolver: ContentResolver, uri: Uri, maxLongSide: Int = 3000): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        val src = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(1f * maxLongSide / w, 1f * maxLongSide / h)
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
            decoder.isMutableRequired = false
        }
    } else {
        // 仅为兼容：老系统可用 BitmapFactory（本项目 minSdk=26 也能走这里）
        val input = resolver.openInputStream(uri)!!
        return BitmapFactory.decodeStream(input)!!
    }
}
