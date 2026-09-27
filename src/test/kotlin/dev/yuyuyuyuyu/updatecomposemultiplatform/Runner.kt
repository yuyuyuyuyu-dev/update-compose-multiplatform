package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File
import java.nio.file.Files

class Runner(
    private val directory: File,
    private val git: Git,
    gh: FakeGh,
) {
    private val script = File("scripts/update-compose-multiplatform.main.kts").absoluteFile

    private val environment =
        git.environment +
            mapOf(
                "PATH" to "${gh.bin}${File.pathSeparator}${System.getenv("PATH")}",
                "RUNNER_TEMP" to directory.resolve("temp").path,
                "GITHUB_REPOSITORY" to "octo-org/octo-app",
                "BASE" to "main",
                "BRANCH" to "chore/update-compose-multiplatform",
            )

    init {
        directory.mkdirs()
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

    fun run(
        workspace: File,
        command: String,
        variables: Map<String, String> = emptyMap(),
    ) {
        val output = Files.createTempFile(directory.toPath(), "output", "").toString()
        val step = environment + mapOf("GITHUB_OUTPUT" to output) + variables
        execute(listOf("kotlin", script.path, command), workspace, step)
    }
}
