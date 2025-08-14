package com.zzsyp.livecompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 开发调试信息显示组件（HUD - Head-Up Display）
 * 用于在界面顶部展示实时运行数据，包括延迟、帧率、设备状态等调试信息
 * 便于开发阶段监控应用性能和运行状态
 */
@Composable
fun DevHud(
    latencyMs: Long,
    feedHz: Float,
    inferHz: Float,
    shake: String,
    flavor: String,
    zSuggested: Float
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Surface(tonalElevation = 6.dp) {
                Column(Modifier.padding(10.dp)) {
                    Text("latency: ${latencyMs}ms")
                    Text("feedHz: ${"%.1f".format(feedHz)}  inferHz: ${"%.1f".format(inferHz)}")
                    Text("shake: $shake  z~${"%.2f".format(zSuggested)}")
                    Text("flavor: $flavor")
                }
            }
        }
    }
}
