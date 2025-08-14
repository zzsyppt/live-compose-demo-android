package com.zzsyp.livecompose.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存到相册（MediaStore）与位图保存工具
 * 兼容 Android 10+（API 29）无存储权限。
 * Redmi K60 Pro（Android 14）直接可用
 */
fun generateName(prefix: String = "LiveCompose"): String {
    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    return "${prefix}_${sdf.format(Date())}"
}

/** 创建拍照用的 OutputFileOptions，保存到 DCIM/LiveComposeDemo 目录 */
fun createOutputOptions(context: Context, displayName: String): ImageCapture.OutputFileOptions {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LiveComposeDemo")
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create MediaStore record")
    return ImageCapture.OutputFileOptions.Builder(resolver, uri, ContentValues()).build()
}

/** 将 Bitmap 另存为 JPEG 到相册（DCIM/LiveComposeDemo） */
fun saveBitmapToAlbum(context: Context, bitmap: Bitmap, displayName: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LiveComposeDemo")
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create MediaStore record for bitmap")
    resolver.openOutputStream(uri).use { out: OutputStream? ->
        if (out == null) error("OutputStream null for $uri")
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    return uri
}
