package dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support

import dev.yuyuyuyuyu.updatecomposemultiplatform.RunnerPaths
import java.io.File
import java.nio.file.Files

class FakeRunner : AutoCloseable {
    private val temp: File = Files.createTempDirectory("runner").toFile()
    private val event: File = temp.resolve("event.json").apply { writeText("""{"repository":{"default_branch":"main"}}""") }
    val log = StringBuilder()

    fun paths(workspace: File): RunnerPaths = RunnerPaths(workspace = workspace, temp = temp, event = event)

    override fun close() {
        temp.deleteRecursively()
    }
}
