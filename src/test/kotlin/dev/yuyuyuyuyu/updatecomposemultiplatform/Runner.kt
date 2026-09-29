package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File
import java.nio.file.Files

class Runner(
    private val directory: File,
    private val git: Git,
    gh: FakeGh,
) {
    val temp: File = directory.resolve("temp")

    private val script = File("scripts/update-compose-multiplatform.main.kts").absoluteFile

    private val environment =
        git.environment +
            mapOf(
                "PATH" to "${gh.bin}${File.pathSeparator}${System.getenv("PATH")}",
                "GITHUB_REPOSITORY" to "octo-org/octo-app",
            )

    init {
        temp.mkdirs()
    }

    fun checkOut(
        repository: Repository,
        ref: String,
    ): File {
        val workspace = Files.createTempDirectory(directory.toPath(), "workspace").toFile()
        git.run(workspace, "clone", "--quiet", repository.directory.path, ".")
        git.run(workspace, "checkout", "--quiet", ref)
        return workspace
    }

    fun output(): File = Files.createTempFile(directory.toPath(), "output", "").toFile()

    fun run(
        workspace: File,
        command: String,
        options: Map<String, String>,
    ) {
        val arguments = options.flatMap { (name, value) -> listOf("--$name", value) }
        execute(listOf("kotlin", script.path, command) + arguments, workspace, environment)
    }
}
