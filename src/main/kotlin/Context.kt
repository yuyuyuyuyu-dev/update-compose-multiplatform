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

class JobFailure(
    message: String,
) : Exception(message)

fun runningJob(job: () -> Unit): Result<Unit> =
    try {
        Result.success(job())
    } catch (failure: IllegalStateException) {
        val errors =
            failure.message
                .orEmpty()
                .lines()
                .joinToString("\n") { "::error::$it" }
        Result.failure(JobFailure(errors))
    }
