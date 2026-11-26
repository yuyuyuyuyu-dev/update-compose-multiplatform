package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yuyuyuyuyu.updatecomposemultiplatform.GitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.RunContext
import dev.yuyuyuyuyu.updatecomposemultiplatform.Sources
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.assertEquals

const val MANUAL_BRANCH = "chore/update-dependencies"
const val AUTO_MERGE_BRANCH = "chore/update-dependencies-auto-merge"
const val CATALOG = "gradle/libs.versions.toml"

data class Outcome(
    val branches: Map<String, Set<String>>,
    val requests: List<String> = emptyList(),
    val gradle: List<String> = emptyList(),
)

class RunResult(
    val exitCode: Int,
    val output: String,
) {
    fun assertSucceeded() {
        assertEquals(0, exitCode, output)
    }
}

class TestWorld : AutoCloseable {
    private val root: File = Files.createTempDirectory("update-compose-multiplatform").toFile()
    val origin: File = root.resolve("origin.git")
    val gradleLog: File = root.resolve("gradlew.log")
    val handover: File = root.resolve("updates.json")
    private val event = root.resolve("event.json")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val url get() = "http://127.0.0.1:${server.address.port}"
    private val sources get() = Sources("$url/changelog.md", listOf("central", "google", "plugins").map { "$url/maven/$it" })
    private lateinit var initialMain: String
    private var inputs: Map<String, String> = emptyMap()

    val maven = FakeMaven()
    val github = FakeGitHub()
    var changelog: String = checkNotNull(TestWorld::class.java.getResource("/support/changelog/paired.md")).readText()

    init {
        server.createContext(
            "/maven/",
        ) { exchange -> respond(exchange, maven.metadata(exchange.requestURI.path.removePrefix("/maven/"))) }
        server.createContext("/changelog.md") { exchange -> respond(exchange, changelog) }
        server.createContext("/api/") { exchange -> github.handle(exchange) }
        server.start()
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", origin.path)
        gradleLog.createNewFile()
        event.writeText("""{"repository":{"default_branch":"main"}}""")
    }

    fun commit(
        files: Map<String, String>,
        projectDirectory: String = ".",
        gradlew: String = fakeGradlew(gradleLog),
    ) {
        val fixture = root.resolve("fixture")
        files.forEach { (path, content) -> fixture.resolve(path).apply { parentFile.mkdirs() }.writeText(content) }
        fixture.resolve(projectDirectory).resolve("gradlew").apply {
            parentFile.mkdirs()
            writeText(gradlew)
            setExecutable(true)
        }
        git(fixture, "init", "--quiet", "--initial-branch=main")
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

    fun inputs(vararg values: Pair<String, String>) {
        inputs = values.toMap()
    }

    fun update(): RunResult =
        runJob { context, output ->
            UpdateJob(context, sources, output).run(
                inputs["project-directory-to-update"],
                inputs["auto-merge"],
                inputs["auto-merge-update-types"],
            )
        }

    fun publish(): RunResult {
        val gradleCalls = gradleLog.readLines()
        val github = GitHub("$url/api", "$url/api/graphql", REPOSITORY, "fake-app-token")
        val result =
            runJob { context, output ->
                PublishJob(context, sources, github, "fake-app", output).run(
                    inputs["project-directory-to-update"],
                    inputs["auto-merge"],
                    inputs["auto-merge-update-types"],
                )
            }
        check(gradleLog.readLines() == gradleCalls) { "The publish job ran gradlew.\n${result.output}" }
        return result
    }

    fun outcome(): Outcome {
        val refs = git(origin, "for-each-ref", "--format=%(refname)").lines().filter(String::isNotEmpty)
        return Outcome(
            refs.associate { ref ->
                ref.removePrefix("refs/heads/") to
                    git(origin, "diff", "--name-only", initialMain, ref).lines().filter(String::isNotEmpty).toSet()
            },
            github.requests.toList(),
            gradleLog.readLines(),
        )
    }

    fun file(
        branch: String,
        path: String,
    ): String = git(origin, "show", "$branch:$path", trim = false)

    fun commits(branch: String): List<String> = git(origin, "log", "--format=%an <%ae> %s", "$initialMain..$branch").lines()

    override fun close() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun runJob(job: (RunContext, Appendable) -> Int): RunResult {
        val workspace = root.resolve("workspace-${System.nanoTime()}")
        git(root, "clone", "--quiet", "--depth=1", "--branch", "main", "file://${origin.path}", workspace.path)
        val output = StringBuilder()
        val exitCode = job(RunContext(workspace, root, event), output)
        return RunResult(exitCode, output.toString())
    }

    private fun respond(
        exchange: HttpExchange,
        body: String?,
    ) {
        val bytes = body?.toByteArray()
        exchange.sendResponseHeaders(if (bytes == null) 404 else 200, bytes?.size?.toLong() ?: -1)
        bytes?.let { exchange.responseBody.write(it) }
        exchange.close()
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
