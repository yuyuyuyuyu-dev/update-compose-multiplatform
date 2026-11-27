package dev.yuyuyuyuyu.updatecomposemultiplatform.support

data class Outcome(
    val changedFiles: Map<String, Set<String>>,
    val gitHubWrites: List<String> = emptyList(),
    val gradleCalls: List<String> = emptyList(),
)
