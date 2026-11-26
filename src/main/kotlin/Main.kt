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
        val settings = Settings(environment, workspace)
        when (arguments.singleOrNull()) {
            "update" -> UpdateJob(settings, workspace, output).run()
            "publish" -> PublishJob(settings, Publishing(environment), workspace, output).run()
            else -> error("Pass update or publish.")
        }
        0
    } catch (failure: IllegalStateException) {
        failure.message
            .orEmpty()
            .lines()
            .forEach { output.appendLine("::error::$it") }
        1
    }
