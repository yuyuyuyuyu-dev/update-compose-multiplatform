package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.support.git
import java.io.File
import java.nio.file.Files

class FakeReleaseRepository : AutoCloseable {
    private val root: File = Files.createTempDirectory("release-repository").toFile()
    private val origin = root.resolve("origin.git")
    private lateinit var releaseTag: String

    fun release(tag: String) {
        releaseTag = tag
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", origin.path)
        val fixture = root.resolve("fixture")
        git(root, "init", "--quiet", "--initial-branch=main", fixture.path)
        commit(fixture, "Earlier release")
        git(fixture, "tag", "v1.9.0")
        git(fixture, "tag", "v1")
        commit(fixture, "Prerelease")
        git(fixture, "tag", "v1.9.1-rc.1")
        commit(fixture, "New release")
        git(fixture, "tag", tag)
        git(fixture, "push", "--quiet", origin.path, "main", "--tags")
    }

    fun checkout(): File {
        val workspace = root.resolve("workspace")
        git(root, "init", "--quiet", "--initial-branch=main", workspace.path)
        git(workspace, "remote", "add", "origin", origin.path)
        git(workspace, "fetch", "--quiet", "--depth=1", "origin", "+refs/tags/$releaseTag:refs/tags/$releaseTag")
        git(workspace, "checkout", "--quiet", "--force", "refs/tags/$releaseTag")
        return workspace
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
