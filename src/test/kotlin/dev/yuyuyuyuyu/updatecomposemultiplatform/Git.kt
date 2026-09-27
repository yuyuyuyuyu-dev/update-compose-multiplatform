package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File

class Git(
    directory: File,
) {
    val environment: Map<String, String> =
        mapOf(
            "GIT_CONFIG_GLOBAL" to directory.resolve("gitconfig").apply { writeText("") }.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
        )

    fun run(
        directory: File,
        vararg arguments: String,
    ): String = execute(listOf("git") + arguments, directory, environment)
}
