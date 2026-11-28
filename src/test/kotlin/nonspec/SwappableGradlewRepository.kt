package dev.yuyuyuyuyu.updatecomposemultiplatform.nonspec

import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.fakeGradlew
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.git
import java.io.File
import java.nio.file.Files

class SwappableGradlewRepository : AutoCloseable {
    private val root: File = Files.createTempDirectory("repository").toFile()
    private val fixture = root.resolve("fixture")
    private val origin: File = root.resolve("origin.git")
    private val gradleLog: File = root.resolve("gradlew.log")
    private lateinit var initialMain: String

    init {
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", origin.path)
        gradleLog.createNewFile()
    }

    fun gradlewThatAlsoWritesReadme(): String = fakeGradlew(gradleLog) + "echo \"Written by gradlew\" > README.md\n"

    fun commit(
        files: Map<String, String>,
        gradlew: String = fakeGradlew(gradleLog),
    ) {
        files.forEach { (path, content) -> fixture.resolve(path).apply { parentFile.mkdirs() }.writeText(content) }
        fixture.resolve("gradlew").apply {
            parentFile.mkdirs()
            writeText(gradlew)
            setExecutable(true)
        }
        git(root, "init", "--quiet", "--initial-branch=main", fixture.path)
        git(fixture, "add", "--all")
        git(
            fixture,
            "-c",
            "user.name=Octocat",
            "-c",
            "user.email=octocat@example.com",
            "commit",
            "--quiet",
            "--message",
            "Add the project",
        )
        git(fixture, "push", "--quiet", origin.path, "main")
        initialMain = git(fixture, "rev-parse", "HEAD")
    }

    fun checkout(): File {
        val workspace = root.resolve("workspace-${System.nanoTime()}")
        git(root, "clone", "--quiet", "--depth=1", "--branch", "main", "file://${origin.path}", workspace.path)
        return workspace
    }

    fun file(
        branch: String,
        path: String,
    ): String = git(origin, "show", "$branch:$path", trim = false)

    fun changedFiles(): Map<String, Set<String>> =
        git(origin, "for-each-ref", "--format=%(refname)")
            .lines()
            .filter(String::isNotEmpty)
            .associate { ref ->
                ref.removePrefix("refs/heads/") to
                    git(origin, "diff", "--name-only", initialMain, ref).lines().filter(String::isNotEmpty).toSet()
            }

    fun gradleCalls(): List<String> = gradleLog.readLines()

    override fun close() {
        root.deleteRecursively()
    }
}
