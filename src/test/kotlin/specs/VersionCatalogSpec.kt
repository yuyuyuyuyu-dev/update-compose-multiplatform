package dev.yuyuyuyuyu.updatecomposemultiplatform.specs

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
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.resolvedLockFile
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionCatalogSpec {
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
    fun `should update the version catalog`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to
                    """
                    [versions]
                    composeMultiplatform = "1.10.1"
                    kotlin = "2.3.0"

                    [libraries]
                    okio = "com.squareup.okio:okio:3.15.0"
                    coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version = "3.2.0" }
                    kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

                    [plugins]
                    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                    kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
                    androidApplication = "com.android.application:8.13.0"
                    """.trimIndent() + "\n",
            ),
        )
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        maven.publish("io.coil-kt.coil3:coil-compose", "3.2.0", "3.3.0")
        maven.publish("org.jetbrains.kotlin:kotlin-test", "2.3.0", "2.3.20")
        maven.publishPlugin("org.jetbrains.kotlin.multiplatform", "2.3.0", "2.3.20")
        maven.publishPlugin("com.android.application", "8.13.0", "8.13.1", repository = "google")
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
                composeMultiplatform = "1.10.1"
                kotlin = "2.3.20"

                [libraries]
                okio = "com.squareup.okio:okio:3.16.0"
                coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version = "3.3.0" }
                kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

                [plugins]
                composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
                androidApplication = "com.android.application:8.13.1"
                """.trimIndent() + "\n",
            actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG),
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
    fun `should choose the versions of the libraries paired with Compose Multiplatform from its version`() {
        // Arrange
        repository.commit(
            mapOf(CATALOG to everyLibraryGroup("1.9.0", "1.9.0-beta06", "1.2.0-alpha06", "2.9.6", "2.9.0")),
        )
        maven.publish(
            "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
            "2.9.6",
            "2.10.0",
            "2.11.0",
        )
        maven.publish("org.jetbrains.androidx.navigation:navigation-compose", "2.9.0", "2.9.2", "2.10.0")
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
            expected = everyLibraryGroup("1.10.1", "1.10.0-alpha06", "1.2.0", "2.10.0-alpha06", "2.9.2"),
            actual = repository.file(MANUAL_MERGE_BRANCH, CATALOG),
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
    fun `should update a project in a subdirectory`() {
        // Arrange
        repository.commit(
            mapOf(
                "README.md" to "An example\n",
                "example/$CATALOG" to catalog(ktor = "3.2.0"),
                "example/kotlin-js-store/yarn.lock" to "Resolved before\n",
            ),
            projectDirectory = "example",
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
        val updated = update.run(projectDirectoryToUpdate = "example")
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app", projectDirectoryToUpdate = "example")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertEquals(expected = catalog(ktor = "3.3.0"), actual = repository.file(MANUAL_MERGE_BRANCH, "example/$CATALOG"))
        assertEquals(
            expected = resolvedLockFile("kotlinUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_MERGE_BRANCH, "example/kotlin-js-store/yarn.lock"),
        )
        assertEquals(
            expected =
                Outcome(
                    changedFiles =
                        mapOf(
                            "main" to emptySet(),
                            MANUAL_MERGE_BRANCH to setOf("example/$CATALOG", "example/kotlin-js-store/yarn.lock"),
                        ),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                    gradleCalls = listOf("kotlinUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
