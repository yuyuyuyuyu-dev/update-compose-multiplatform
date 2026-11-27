package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.Sources
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeChangelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.MANUAL_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.catalog
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandoverTest {
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

    private val sources = Sources(changelog.url, maven.urls)

    @Test
    fun `should publish nothing when the default branch moves while the lock files are updated`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run()
        repository.pushToMain(mapOf("README.md" to "An app\n"))
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertTrue(
            runner.output.lines().contains(
                "::warning::The default branch moved while the lock files were being updated, " +
                    "so nothing is published. The next run updates it.",
            ),
        )
        assertEquals(
            expected = Outcome(changedFiles = mapOf("main" to setOf("README.md")), gradleCalls = listOf("kotlinUpgradeYarnLock")),
            actual = outcome(),
        )
    }

    @Test
    fun `should publish nothing when the updates change while the lock files are updated`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertTrue(actual = published.isSuccess, message = published.exceptionOrNull()?.message)
        assertTrue(
            runner.output.lines().contains(
                "::warning::The updates changed while the lock files were being updated, " +
                    "so nothing is published. The next run picks them up.",
            ),
        )
        assertEquals(
            expected = Outcome(changedFiles = mapOf("main" to emptySet()), gradleCalls = listOf("kotlinUpgradeYarnLock")),
            actual = outcome(),
        )
    }

    @Test
    fun `should fail without publishing when the update job hands over a file other than the lock files`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        runner.editHandover {
            it.replace("{\"$CATALOG\":", "{\".github/workflows/ci.yml\":\"Planted\",\"$CATALOG\":")
        }
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertTrue(actual = updated.isSuccess, message = updated.exceptionOrNull()?.message)
        assertEquals(
            expected = "::error::The update job did not hand over just the version catalog and the lock files for $MANUAL_BRANCH.",
            actual = published.exceptionOrNull()?.message,
        )
        assertEquals(
            expected = Outcome(changedFiles = mapOf("main" to emptySet()), gradleCalls = listOf("kotlinUpgradeYarnLock")),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome =
        Outcome(changedFiles = repository.changedFiles(), gitHubWrites = github.writes.toList(), gradleCalls = repository.gradleCalls())
}
