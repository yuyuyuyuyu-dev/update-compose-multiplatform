package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    exitProcess(runJob(arguments.toList(), System.getenv(), System.out))
}

fun runJob(
    arguments: List<String>,
    environment: Map<String, String>,
    output: Appendable,
): Int =
    try {
        val workspace = Workspace(File(required(environment, "GITHUB_WORKSPACE")), environment)
        when (arguments.singleOrNull()) {
            "update" -> UpdateJob(Settings(environment, workspace), workspace, output).run()
            "publish" -> PublishJob(Settings(environment, workspace), Publishing(environment), workspace, output).run()
            "move-major-tag" -> MoveMajorTagJob(eventField(environment, "release", "tag_name"), workspace).run()
            else -> error("Pass update, publish or move-major-tag.")
        }
        0
    } catch (failure: IllegalStateException) {
        failure.message
            .orEmpty()
            .lines()
            .forEach { output.appendLine("::error::$it") }
        1
    }
