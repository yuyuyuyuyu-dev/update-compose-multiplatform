package dev.yuyuyuyuyu.updatecomposemultiplatform.nonspec

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.VersionSources
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.catalog
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.everyLibraryGroup
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailureTest {
    @AutoClose
    private val repository = FakeRepository()

    @AutoClose
    private val maven = FakeMaven()

    @AutoClose
    private val changelog = EditableChangelog()

    @AutoClose
    private val github = FakeGitHub()

    @AutoClose
    private val runner = FakeRunner()

    private val versionSources = VersionSources(changelogUrl = changelog.url, mavenRepositoryUrls = maven.urls)

    private val nothingChanged = Outcome(changedFiles = mapOf("main" to emptySet()))

    @Test
    fun `should fail without changing anything when no token of the GitHub App is passed`() {
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
        val published = publish.run(token = "", appSlug = "")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertEquals(
            expected =
                "::error::Pass the app-client-id input and the app-private-key secret, " +
                    "so that the workflow can create a token for the GitHub App.",
            actual = published.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when auto-merge is not a merge method`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run(autoMerge = "fast-forward", autoMergeUpdateTypes = "minor")

        // Assert
        assertEquals(
            expected = "::error::auto-merge must be disable, squash, merge or rebase.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when auto-merge has no valid update types`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "minor,prerelease")

        // Assert
        assertEquals(
            expected =
                "::error::auto-merge-update-types must list the update types to merge automatically, " +
                    "such as minor,patch, unless auto-merge is disable.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when the project directory has no version catalog`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run(projectDirectoryToUpdate = "example")

        // Assert
        assertEquals(
            expected =
                "::error::example/gradle/libs.versions.toml does not exist. " +
                    "Pass the directory of the Compose Multiplatform project as project-directory-to-update.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when the catalog has no Compose Multiplatform`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [libraries]
                    okio = "com.squareup.okio:okio:3.15.0"
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(
            expected =
                "::error::gradle/libs.versions.toml has no org.jetbrains.compose plugin " +
                    "or compose-gradle-plugin library with a version. " +
                    "This workflow is only for projects that take Compose Multiplatform from the version catalog.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG lists no stable release`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        changelog.text = changelogNamed("no-stable-release.md")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(
            expected = "::error::The Compose Multiplatform CHANGELOG lists no stable release.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG has no version of a paired library`() {
        // Arrange
        repository.commit(
            mapOf(CATALOG to everyLibraryGroup("1.9.0", "1.9.0-beta06", "1.2.0-alpha06", "2.9.6", "2.9.0")),
        )
        changelog.text = changelogNamed("no-material3.md")
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(
            expected = "::error::The Compose Multiplatform CHANGELOG section for 1.10.1 has no Material3 version that can be read.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    @Test
    fun `should fail without changing anything when a version key is paired with different versions`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform = "1.9.0"

                    [libraries]
                    compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "composeMultiplatform" }

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    """.trimIndent() + "\n",
            ),
        )
        val update =
            UpdateJob(runnerPaths = runner.paths(repository.checkout()), versionSources = versionSources, logDestination = runner.log)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(
            expected =
                "::error::Material3 and Compose Multiplatform share versions.composeMultiplatform, " +
                    "but the Compose Multiplatform CHANGELOG gives them different versions: 1.10.0-alpha06 and 1.10.1.",
            actual = updated.exceptionOrNull()?.message,
        )
        assertEquals(expected = nothingChanged, actual = outcome())
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
