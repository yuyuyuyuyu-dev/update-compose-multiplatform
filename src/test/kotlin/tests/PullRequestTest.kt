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

class PullRequestTest {
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

    private val nothingChanged = Outcome(changedFiles = mapOf("main" to emptySet()))

    @Test
    fun `should do nothing when no dependency has an update`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
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
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should update the open pull request instead of opening another`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        github.openPullRequest(
            MANUAL_MERGE_BRANCH,
            "build(deps): update io.ktor:ktor-client-core to 3.2.1",
            "Old body",
        )
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
            expected = "build(deps): update io.ktor:ktor-client-core to 3.3.0",
            actual = github.pullRequest(MANUAL_MERGE_BRANCH).title,
        )
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites = listOf("PATCH /repos/$REPOSITORY/pulls/1"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should leave the open pull request and its branch alone when they already have the update`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log).run()
        PublishJob(
            runnerPaths = runner.paths(repository.checkout()),
            versionSources = versionSources,
            endpoint = github.endpoint,
            logDestination = runner.log,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        val commits = repository.commits(MANUAL_MERGE_BRANCH)
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
        assertEquals(expected = commits, actual = repository.commits(MANUAL_MERGE_BRANCH))
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
    fun `should close the pull request and delete its branch when no update is left`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log).run()
        PublishJob(
            runnerPaths = runner.paths(repository.checkout()),
            versionSources = versionSources,
            endpoint = github.endpoint,
            logDestination = runner.log,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        maven.publish("io.ktor:ktor-client-core", "3.2.0")
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
        assertEquals(expected = "closed", actual = pullRequest.state)
        assertEquals(expected = listOf("Closing because main needs none of these updates any more."), actual = pullRequest.comments)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet()),
                    gitHubWrites =
                        listOf(
                            "POST /repos/$REPOSITORY/pulls",
                            "POST /repos/$REPOSITORY/issues/1/comments",
                            "PATCH /repos/$REPOSITORY/pulls/1",
                        ),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should turn off auto-merge on a pull request without auto-merge when its updates change`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log).run()
        PublishJob(
            runnerPaths = runner.paths(repository.checkout()),
            versionSources = versionSources,
            endpoint = github.endpoint,
            logDestination = runner.log,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        github.pullRequest(MANUAL_MERGE_BRANCH).autoMerge = "squash"
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
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
        assertEquals(expected = null, actual = github.pullRequest(MANUAL_MERGE_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    changedFiles = mapOf("main" to emptySet(), MANUAL_MERGE_BRANCH to setOf(CATALOG)),
                    gitHubWrites =
                        listOf(
                            "POST /repos/$REPOSITORY/pulls",
                            "PATCH /repos/$REPOSITORY/pulls/1",
                            "POST /graphql disablePullRequestAutoMerge",
                        ),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
