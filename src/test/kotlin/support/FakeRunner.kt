package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import dev.yuyuyuyuyu.updatecomposemultiplatform.RunContext
import java.io.File
import java.nio.file.Files

class FakeRunner : AutoCloseable {
    val temp: File = Files.createTempDirectory("runner").toFile()
    val event: File = temp.resolve("event.json").apply { writeText("""{"repository":{"default_branch":"main"}}""") }
    val output = StringBuilder()

    fun context(workspace: File): RunContext = RunContext(workspace, temp, event)

    override fun close() {
        temp.deleteRecursively()
    }
}
