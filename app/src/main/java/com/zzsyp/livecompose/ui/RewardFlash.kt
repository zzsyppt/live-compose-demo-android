package com.zzsyp.livecompose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 该文件包含直播相关的UI组件，主要实现了奖励动画效果的UI元素。
 *
 * 其中提供的RewardFlash组件用于实现奖励提示时的闪烁效果，通过Compose动画系统
 * 控制白色半透明遮罩的透明度变化，营造出类似闪光的视觉效果，增强用户在获得
 * 奖励时的视觉反馈体验。
 */
@Composable
fun RewardFlash() {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(500))
    }
    val alpha = (1f - anim.value).coerceIn(0f, 1f) * 0.6f
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha)))
}
