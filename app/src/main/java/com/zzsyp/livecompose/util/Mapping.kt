package com.zzsyp.livecompose.util

import android.graphics.RectF
import com.zzsyp.livecompose.ml.CropResult

fun centerToLTRB(r: CropResult): RectF {
    val l = (r.cx - r.w/2f).coerceIn(0f, 1f)
    val t = (r.cy - r.h/2f).coerceIn(0f, 1f)
    val rt = (r.cx + r.w/2f).coerceIn(0f, 1f)
    val b = (r.cy + r.h/2f).coerceIn(0f, 1f)
    return RectF(l, t, rt, b)
}

/** 将归一化 [0,1] 的 LTRB 映射到某个 contentRect（像素） */
fun mapToView(normalized: RectF, content: RectF): RectF {
    val left   = content.left + normalized.left  * content.width()
    val top    = content.top  + normalized.top   * content.height()
    val right  = content.left + normalized.right * content.width()
    val bottom = content.top  + normalized.bottom* content.height()
    return RectF(left, top, right, bottom)
}
