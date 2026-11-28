package dev.yuyuyuyuyu.updatecomposemultiplatform.specs

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.VersionSources
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeChangelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakePullRequest
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

class PullRequestSpec {
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
    fun `should open a pull request`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
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
        val pullRequest = github.pullRequest(MANUAL_MERGE_BRANCH)
        assertEquals(
            expected =
                FakePullRequest(
                    1,
                    MANUAL_MERGE_BRANCH,
                    "main",
                    "build(deps): update io.ktor:ktor-client-core to 3.3.0",
                    pullRequest.body,
                ),
            actual = pullRequest,
        )
        assertEquals(
            expected =
                listOf(
                    "fake-app[bot] <41898282+fake-app[bot]@users.noreply.github.com> " +
                        "build(deps): update io.ktor:ktor-client-core to 3.3.0",
                ),
            actual = repository.commits(MANUAL_MERGE_BRANCH),
        )
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should describe the updates in the pull request body`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(compose = "1.10.0", ktor = "3.2.0", okio = "3.15.0"),
                "kotlin-js-store/yarn.lock" to "Resolved before\n",
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
                Updates the dependencies in `gradle/libs.versions.toml`. The Update Compose Multiplatform workflow updates this pull request on each run and closes it when it is no longer needed.

                | Dependency | Version | From | To |
                | --- | --- | --- | --- |
                | `org.jetbrains.compose` | `versions.composeMultiplatform` | `1.10.0` | `1.10.1` |
                | `io.ktor:ktor-client-core` | `versions.ktor` | `3.2.0` | `3.3.0` |
                | `com.squareup.okio:okio` | `libraries.okio` | `3.15.0` | `3.16.0` |

                - Compose Multiplatform is the latest release in the Compose Multiplatform CHANGELOG that it can move to, and the libraries paired with it are the versions that the CHANGELOG lists for that release.
                - The other dependencies are the latest versions in Maven Central, the Google Maven repository and the Gradle Plugin Portal.
                - A stable version moves only to stable versions. A prerelease moves to a later prerelease of the same version, or to a stable version.
                - The lock file `kotlin-js-store/yarn.lock` was updated with `./gradlew kotlinUpgradeYarnLock`.
                """.trimIndent() + "\n",
            actual = github.pullRequest(MANUAL_MERGE_BRANCH).body,
        )
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

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
