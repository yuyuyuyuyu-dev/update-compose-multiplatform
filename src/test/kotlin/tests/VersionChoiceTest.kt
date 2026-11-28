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
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.everyLibraryGroup
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionChoiceTest {
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
    fun `should leave the paired libraries alone while Compose Multiplatform is not updated`() {
        // Arrange
        repository.commit(mapOf(CATALOG to everyLibraryGroup("1.10.1", "1.10.0-alpha06", "1.2.0", "2.9.6", "2.9.2")))
        maven.publish("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", "2.9.6", "2.10.0")
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
    fun `should update a Compose Multiplatform prerelease to the latest prerelease of its version`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    everyLibraryGroup(
                        "1.11.0-alpha01",
                        "1.11.0-alpha01",
                        "1.3.0-alpha01",
                        "2.10.0-alpha07",
                        "2.9.1",
                    ),
            ),
        )
        changelog.text = changelogNamed("second-beta.md")
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
            expected = everyLibraryGroup("1.11.0-beta02", "1.11.0-beta02", "1.3.0-beta02", "2.11.0-beta02", "2.10.0-beta02"),
            actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG),
        )
        assertEquals(
            expected = "build(deps): update Compose Multiplatform to 1.11.0-beta02",
            actual = github.pullRequest(MANUAL_MERGE_BRANCH).title,
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
    fun `should keep a version newer than any that the repositories have`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.5.0")))
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
    fun `should choose a version that every module sharing the version key has`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform = "1.10.1"
                    ktor = "3.2.0"

                    [libraries]
                    ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
                    ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
        maven.publish("io.ktor:ktor-client-cio", "3.2.0", "3.3.0")
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
        assertTrue(repository.file(MANUAL_MERGE_BRANCH, CATALOG).contains("ktor = \"3.3.0\""))
        assertEquals(expected = "build(deps): update ktor to 3.3.0", actual = github.pullRequest(MANUAL_MERGE_BRANCH).title)
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
    fun `should leave a dependency alone and warn when no repository has it`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0", okio = "3.15.0")))
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
        assertTrue(
            runner.log.lines().contains(
                "::warning::versions.ktor is not updated, because no repository has io.ktor:ktor-client-core.",
            ),
        )
        assertEquals(expected = catalog(ktor = "3.2.0", okio = "3.16.0"), actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG))
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
    fun `should leave dynamic, rich and missing versions alone`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform = "1.10.1"
                    ktor = "3.+"

                    [libraries]
                    ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
                    okio = { module = "com.squareup.okio:okio", version = { strictly = "3.15.0" } }
                    okio-fakefilesystem = { module = "com.squareup.okio:okio-fakefilesystem" }

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        maven.publish("com.squareup.okio:okio-fakefilesystem", "3.15.0", "3.16.0")
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
    fun `should keep the flavor of a version`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform = "1.10.1"

                    [libraries]
                    guava = "com.google.guava:guava:33.0.0-jre"

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish(
            "com.google.guava:guava",
            "33.0.0-jre",
            "33.0.0-android",
            "33.1.0-jre",
            "33.1.0-android",
            "33.2.0-android",
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
            expected = "build(deps): update com.google.guava:guava to 33.1.0-jre",
            actual = github.pullRequest(MANUAL_MERGE_BRANCH).title,
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

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
