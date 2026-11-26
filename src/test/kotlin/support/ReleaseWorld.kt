package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import dev.yuyuyuyuyu.updatecomposemultiplatform.runJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files

class ReleaseWorld(
    private val releaseTag: String,
) : AutoCloseable {
    private val root: File = Files.createTempDirectory("move-major-tag").toFile()
    private val origin = root.resolve("origin.git")
    private val event = root.resolve("event.json")

    init {
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", origin.path)
        val fixture = root.resolve("fixture")
        git(root, "init", "--quiet", "--initial-branch=main", fixture.path)
        commit(fixture, "Earlier release")
        git(fixture, "tag", "v1.9.0")
        git(fixture, "tag", "v1")
        commit(fixture, "Prerelease")
        git(fixture, "tag", "v1.9.1-rc.1")
        commit(fixture, "New release")
        git(fixture, "tag", releaseTag)
        git(fixture, "push", "--quiet", origin.path, "main", "--tags")
        val release = JsonObject(mapOf("tag_name" to JsonPrimitive(releaseTag)))
        event.writeText(JsonObject(mapOf("release" to release)).toString())
    }

    fun moveMajorTag(): RunResult {
        val workspace = root.resolve("workspace")
        git(root, "init", "--quiet", "--initial-branch=main", workspace.path)
        git(workspace, "remote", "add", "origin", origin.path)
        git(workspace, "fetch", "--quiet", "--depth=1", "origin", "+refs/tags/$releaseTag:refs/tags/$releaseTag")
        git(workspace, "checkout", "--quiet", "--force", "refs/tags/$releaseTag")
        val environment =
            System.getenv().filterKeys { !it.startsWith("GITHUB_") && !it.startsWith("RUNNER_") } +
                mapOf("GITHUB_WORKSPACE" to workspace.path, "GITHUB_EVENT_PATH" to event.path)
        val output = StringBuilder()
        val exitCode = runJob(listOf("move-major-tag"), environment, output)
        return RunResult(exitCode, output.toString())
    }

    fun tags(): List<String> = git(origin, "for-each-ref", "--format=%(refname:short) %(subject)", "refs/tags").lines()

    override fun close() {
        root.deleteRecursively()
    }

    private fun commit(
        directory: File,
        message: String,
    ) {
        git(
            directory,
            "-c",
            "user.name=Octocat",
            "-c",
            "user.email=octocat@example.com",
            "commit",
            "--quiet",
            "--allow-empty",
            "--message",
            message,
        )
    }
}
