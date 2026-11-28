package dev.yuyuyuyuyu.updatecomposemultiplatform.spec

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.VersionSources
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.AUTO_MERGE_BRANCH
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

class AutoMergeSpec {
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
    fun `should enable auto-merge`() {
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
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "major,minor,patch")
        val published =
            publish.run(
                token = "fake-app-token",
                appSlug = "fake-app",
                autoMerge = "squash",
                autoMergeUpdateTypes = "major,minor,patch",
            )

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(expected = "squash", actual = github.pullRequest(AUTO_MERGE_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), AUTO_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls", "POST /graphql enablePullRequestAutoMerge"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should enable auto-merge only for the chosen update types`() {
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
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "patch")
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app", autoMerge = "squash", autoMergeUpdateTypes = "patch")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(expected = null, actual = github.pullRequest(MANUAL_MERGE_BRANCH).autoMerge)
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
    fun `should split the updates into a pull request with auto-merge and one without`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0", okio = "3.15.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "4.0.0")
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
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "minor,patch")
        val published =
            publish.run(
                token = "fake-app-token",
                appSlug = "fake-app",
                autoMerge = "squash",
                autoMergeUpdateTypes = "minor,patch",
            )

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(expected = catalog(ktor = "3.2.0", okio = "3.16.0"), actual = repository.file(AUTO_MERGE_BRANCH, CATALOG))
        assertEquals(expected = catalog(ktor = "4.0.0", okio = "3.15.0"), actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG))
        assertEquals(expected = "squash", actual = github.pullRequest(AUTO_MERGE_BRANCH).autoMerge)
        assertEquals(expected = null, actual = github.pullRequest(MANUAL_MERGE_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), AUTO_MERGE_BRANCH to setOf(CATALOG), MANUAL_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites =
                        listOf(
                            "POST /repos/$REPOSITORY/pulls",
                            "POST /graphql enablePullRequestAutoMerge",
                            "POST /repos/$REPOSITORY/pulls",
                        ),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
