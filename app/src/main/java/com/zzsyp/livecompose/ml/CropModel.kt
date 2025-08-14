package com.zzsyp.livecompose.ml

import android.graphics.Bitmap

interface CropModel {
    suspend fun predict(frame: Bitmap): CropResult
}
