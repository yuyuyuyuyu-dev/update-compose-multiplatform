import java.io.File

class Workspace(
    private val root: File,
    private val environment: Map<String, String>,
) {
    fun file(path: String): File = root.resolve(path)

    fun git(vararg arguments: String): String = git(arguments.toList())

    fun git(arguments: List<String>): String {
        val command = listOf("git") + arguments
        val process = process(command, root).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed." }
        return output
    }

    fun gradlew(
        directory: String,
        tasks: List<String>,
    ) {
        val projectDirectory = file(directory)
        val command = listOf(projectDirectory.resolve("gradlew").absolutePath) + tasks
        val process = process(command, projectDirectory).inheritIO().start()
        check(process.waitFor() == 0) { "${(listOf("./gradlew") + tasks).joinToString(" ")} failed." }
    }

    private fun process(
        command: List<String>,
        directory: File,
    ): ProcessBuilder =
        ProcessBuilder(command).directory(directory).apply {
            environment().clear()
            environment().putAll(this@Workspace.environment)
        }
}
