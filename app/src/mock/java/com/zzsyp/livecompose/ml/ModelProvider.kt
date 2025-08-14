package com.zzsyp.livecompose.ml

/**
 * mock flavor 的模型提供器：
 * - 可以在这里切换 Random/Heuristic两种模拟法则 以对比 UI 体验
 */
object ModelProvider {
    // 切换到 RandomCropModel() 体验“跳动更大、延时可调”的效果
    fun provide(): CropModel = HeuristicCropModel(latencyMsRange = 50L..90L)
}
