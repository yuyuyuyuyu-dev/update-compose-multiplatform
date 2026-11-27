package dev.yuyuyuyuyu.updatecomposemultiplatform.support

data class Outcome(
    val branches: Map<String, Set<String>>,
    val requests: List<String> = emptyList(),
    val gradle: List<String> = emptyList(),
)
