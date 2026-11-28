package dev.yuyuyuyuyu.updatecomposemultiplatform.spec

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.VersionSources
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeChangelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.MANUAL_MERGE_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.REPOSITORY
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.catalog
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrereleaseSpec {
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
    fun `should update a prerelease to a later prerelease`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0-alpha01")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0-alpha01", "3.3.0-beta01", "3.4.0-alpha01")
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
        assertEquals(expected = catalog(ktor = "3.3.0-beta01"), actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG))
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
    fun `should not update a stable release to a prerelease`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.2.1", "3.3.0-rc01")
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
        assertEquals(expected = catalog(ktor = "3.2.1"), actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG))
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
    fun `should update a prerelease to its stable release`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0-rc01")))
        maven.publish("io.ktor:ktor-client-core", "3.3.0-rc01", "3.3.0", "3.4.0-alpha01")
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
        assertEquals(expected = catalog(ktor = "3.3.0"), actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG))
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
