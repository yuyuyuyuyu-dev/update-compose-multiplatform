package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yuyuyuyuyu.updatecomposemultiplatform.runJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val gradleLog = root.resolve("gradlew.log")
    val handover: File = root.resolve("updates.json")
    private val event = root.resolve("event.json")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val url get() = "http://127.0.0.1:${server.address.port}"
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
        gradlew: String = FAKE_GRADLEW,
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

    fun update(): RunResult {
        val callsBefore = github.calls.get()
        val result = runJobIn("update", environment().filterKeys { it !in publishJobSecrets })
        check(github.calls.get() == callsBefore) { "The update job called GitHub.\n${result.output}" }
        return result
    }

    fun publish(): RunResult {
        val publishGradleLog = root.resolve("publish-gradlew.log")
        val result = runJobIn("publish", environment() + ("FAKE_GRADLEW_LOG" to publishGradleLog.path))
        check(!publishGradleLog.exists()) { "The publish job ran gradlew.\n${result.output}" }
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

    private fun runJobIn(
        job: String,
        environment: Map<String, String>,
    ): RunResult {
        val workspace = root.resolve("workspace-${System.nanoTime()}")
        git(root, "clone", "--quiet", "--depth=1", "--branch", "main", "file://${origin.path}", workspace.path)
        val output = StringBuilder()
        val exitCode = runJob(listOf(job), environment + ("GITHUB_WORKSPACE" to workspace.path), output)
        return RunResult(exitCode, output.toString())
    }

    private fun environment(): Map<String, String> =
        System.getenv().filterKeys { !it.startsWith("GITHUB_") && !it.startsWith("RUNNER_") } +
            mapOf(
                "INPUTS" to JsonObject((workflowInputs + inputs).mapValues { JsonPrimitive(it.value) }).toString(),
                "TOKEN" to "fake-app-token",
                "APP_SLUG" to "fake-app",
                "RUNNER_TEMP" to root.path,
                "GITHUB_EVENT_PATH" to event.path,
                "GITHUB_REPOSITORY" to "octo-org/octo-app",
                "GITHUB_API_URL" to "$url/api",
                "GITHUB_GRAPHQL_URL" to "$url/api/graphql",
                "COMPOSE_MULTIPLATFORM_CHANGELOG" to "$url/changelog.md",
                "MAVEN_REPOSITORIES" to listOf("central", "google", "plugins").joinToString("\n") { "$url/maven/$it" },
                "FAKE_GRADLEW_LOG" to gradleLog.path,
            )

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

private val workflowInputs =
    mapOf(
        "jdk-distribution" to "zulu",
        "jdk-version" to "21",
        "project-directory-to-update" to "",
        "auto-merge" to "",
        "auto-merge-update-types" to "",
        "app-client-id" to "",
    )

private val publishJobSecrets = setOf("TOKEN", "APP_SLUG")

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

val FAKE_GRADLEW =
    """
    #!/bin/sh
    set -eu
    echo "${'$'}*" >> "${'$'}FAKE_GRADLEW_LOG"
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
