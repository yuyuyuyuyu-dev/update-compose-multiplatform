package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import java.io.File
import java.nio.file.Files

const val MANUAL_BRANCH = "chore/update-dependencies"
const val AUTO_MERGE_BRANCH = "chore/update-dependencies-auto-merge"
const val CATALOG = "gradle/libs.versions.toml"

class FakeRepository : AutoCloseable {
    private val root: File = Files.createTempDirectory("repository").toFile()
    private val fixture = root.resolve("fixture")
    private lateinit var initialMain: String

    val origin: File = root.resolve("origin.git")
    val gradleLog: File = root.resolve("gradlew.log")

    init {
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", origin.path)
        gradleLog.createNewFile()
    }

    fun commit(
        files: Map<String, String>,
        projectDirectory: String = ".",
        gradlew: String = fakeGradlew(gradleLog),
    ) {
        files.forEach { (path, content) -> fixture.resolve(path).apply { parentFile.mkdirs() }.writeText(content) }
        fixture.resolve(projectDirectory).resolve("gradlew").apply {
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

    fun commits(branch: String): List<String> = git(origin, "log", "--format=%an <%ae> %s", "$initialMain..$branch").lines()

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

fun resolvedLockFile(
    task: String,
    catalog: String,
): String = "Resolved by $task for:\n$catalog"

fun git(
    directory: File,
    vararg arguments: String,
    trim: Boolean = true,
): String {
    val process =
        ProcessBuilder(listOf("git") + arguments)
            .directory(directory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed." }
    return if (trim) output.trim() else output
}

fun fakeGradlew(log: File): String =
    """
    #!/bin/sh
    set -eu
    echo "${'$'}*" >> '${log.path}'
    for task in "${'$'}@"; do
      case "${'$'}task" in
        kotlinUpgradeYarnLock) lock=kotlin-js-store/yarn.lock ;;
        kotlinWasmUpgradeYarnLock) lock=kotlin-js-store/wasm/yarn.lock ;;
        kotlinUpgradePackageLock) lock=kotlin-js-store/package-lock.json ;;
        kotlinWasmUpgradePackageLock) lock=kotlin-js-store/wasm/package-lock.json ;;
        *) echo "The fake gradlew does not support ${'$'}task." >&2; exit 1 ;;
      esac
      { echo "Resolved by ${'$'}task for:"; cat gradle/libs.versions.toml; } > "${'$'}lock"
    done
    """.trimIndent() + "\n"
