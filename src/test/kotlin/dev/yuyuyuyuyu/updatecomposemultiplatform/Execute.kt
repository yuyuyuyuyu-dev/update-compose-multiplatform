package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File

fun execute(
    command: List<String>,
    directory: File,
    variables: Map<String, String> = emptyMap(),
): String {
    val process =
        ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply { environment().putAll(variables) }
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    return output
}
