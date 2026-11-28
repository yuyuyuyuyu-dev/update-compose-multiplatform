package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.VersionSources
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeChangelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.MANUAL_MERGE_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.REPOSITORY
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.catalog
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangedFilesTest {
    @AutoClose
    private val repository = FakeRepository()

    @AutoClose
    private val maven = FakeMaven()

    @AutoClose
    private val changelog = FakeChangelog()

    @AutoClose
    private val github = FakeGitHub()

    @AutoClose
    private val runner = FakeRunner()

    private val versionSources = VersionSources(changelogUrl = changelog.url, mavenRepositoryUrls = maven.urls)

    @Test
    fun `should keep the formatting of the catalog`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform="1.10.1"
                    ktor   =   "3.2.0"

                    [libraries]
                    ktor-client-core = {module="io.ktor:ktor-client-core",version.ref="ktor"}
                    okio={ module = "com.squareup.okio:okio", version = "3.15.0" }

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)
        val publish =
            PublishJob(
                runnerPaths = runner.paths(repository.checkout()),
                versionSources = versionSources,
                endpoint = github.endpoint,
                logDestination = runner.log,
            )

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(
            expected =
                """
                [versions]
                composeMultiplatform="1.10.1"
                ktor   =   "3.3.0"

                [libraries]
                ktor-client-core = {module="io.ktor:ktor-client-core",version.ref="ktor"}
                okio={ module = "com.squareup.okio:okio", version = "3.16.0" }

                [plugins]
                composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                """.trimIndent() + "\n",
            actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG),
        )
    }

    @Test
    fun `should commit nothing but the catalog and the lock files`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "README.md" to "An app\n",
                "kotlin-js-store/yarn.lock" to "Resolved before\n",
            ),
            gradlew = repository.gradlewThatAlsoWritesReadme(),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)
        val publish =
            PublishJob(
                runnerPaths = runner.paths(repository.checkout()),
                versionSources = versionSources,
                endpoint = github.endpoint,
                logDestination = runner.log,
            )

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock")),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                    gradleCalls = listOf("kotlinUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should run only the Gradle tasks of the lock files that the project has`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "kotlin-js-store/wasm/yarn.lock" to "Resolved before\n",
            ),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)
        val publish =
            PublishJob(
                runnerPaths = runner.paths(repository.checkout()),
                versionSources = versionSources,
                endpoint = github.endpoint,
                logDestination = runner.log,
            )

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG, "kotlin-js-store/wasm/yarn.lock")),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                    gradleCalls = listOf("kotlinWasmUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
