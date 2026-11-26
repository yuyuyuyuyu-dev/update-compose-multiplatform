package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File

const val HANDOVER_FILE = "updates.json"

class RunContext(
    val workspace: File,
    val temp: File,
    val event: File,
)

class Sources(
    val changelogUrl: String,
    val mavenRepositoryUrls: List<String>,
)

fun reportingFailures(
    output: Appendable,
    job: () -> Unit,
): Int =
    try {
        job()
        0
    } catch (failure: IllegalStateException) {
        failure.message
            .orEmpty()
            .lines()
            .forEach { output.appendLine("::error::$it") }
        1
    }
