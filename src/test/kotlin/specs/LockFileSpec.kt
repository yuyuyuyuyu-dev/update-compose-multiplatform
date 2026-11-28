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
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.resolvedLockFile
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockFileSpec {
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
    fun `should update the Yarn lock files`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "kotlin-js-store/yarn.lock" to "Resolved before\n",
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
            expected = resolvedLockFile("kotlinUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_MERGE_BRANCH, "kotlin-js-store/yarn.lock"),
        )
        assertEquals(
            expected = resolvedLockFile("kotlinWasmUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_MERGE_BRANCH, "kotlin-js-store/wasm/yarn.lock"),
        )
        assertEquals(
            expected =
                Outcome(
                    changedFiles =
                        mapOf(
                            "main" to emptySet(),
                            MANUAL_MERGE_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock", "kotlin-js-store/wasm/yarn.lock"),
                        ),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                    gradleCalls = listOf("kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should update the npm lock files`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "kotlin-js-store/package-lock.json" to "{}\n",
                "kotlin-js-store/wasm/package-lock.json" to "{}\n",
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
            expected = resolvedLockFile("kotlinUpgradePackageLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_MERGE_BRANCH, "kotlin-js-store/package-lock.json"),
        )
        assertEquals(
            expected = resolvedLockFile("kotlinWasmUpgradePackageLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_MERGE_BRANCH, "kotlin-js-store/wasm/package-lock.json"),
        )
        assertEquals(
            expected =
                Outcome(
                    changedFiles =
                        mapOf(
                            "main" to emptySet(),
                            MANUAL_MERGE_BRANCH to
                                setOf(
                                    CATALOG,
                                    "kotlin-js-store/package-lock.json",
                                    "kotlin-js-store/wasm/package-lock.json",
                                ),
                        ),
                    gitHubWrites = listOf("POST /repos/$REPOSITORY/pulls"),
                    gradleCalls = listOf("kotlinUpgradePackageLock kotlinWasmUpgradePackageLock"),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
