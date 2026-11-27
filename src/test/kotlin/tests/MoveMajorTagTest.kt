package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.MoveMajorTagJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRunner
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveMajorTagTest {
    @AutoClose
    private val repository = FakeReleaseRepository()

    @AutoClose
    private val runner = FakeRunner()

    @Test
    fun `should move the major tag to a new release of the same major version`() {
        // Arrange
        repository.release("v1.9.2")
        runner.release("v1.9.2")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(expected = Result.success(Unit), actual = moved)
        assertEquals(
            expected =
                listOf(
                    "v1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                    "v1.9.2 New release",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should ignore prereleases when it looks for a newer release`() {
        // Arrange
        repository.release("v1.9.1")
        runner.release("v1.9.1")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(expected = Result.success(Unit), actual = moved)
        assertEquals(
            expected =
                listOf(
                    "v1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1 New release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should treat minor version 10 as newer than minor version 9`() {
        // Arrange
        repository.release("v1.10.0")
        runner.release("v1.10.0")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(expected = Result.success(Unit), actual = moved)
        assertEquals(
            expected =
                listOf(
                    "v1 New release",
                    "v1.10.0 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should create the major tag for the first release of a major version`() {
        // Arrange
        repository.release("v2.0.0")
        runner.release("v2.0.0")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(expected = Result.success(Unit), actual = moved)
        assertEquals(
            expected =
                listOf(
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                    "v2 New release",
                    "v2.0.0 New release",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should ignore other major versions when it looks for a newer release`() {
        // Arrange
        repository.release("v0.9.1")
        runner.release("v0.9.1")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(expected = Result.success(Unit), actual = moved)
        assertEquals(
            expected =
                listOf(
                    "v0 New release",
                    "v0.9.1 New release",
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should fail without moving any tag when the same major version has a newer release`() {
        // Arrange
        repository.release("v1.8.0")
        runner.release("v1.8.0")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(
            expected = "::error::v1.8.0 is older than v1.9.0, so v1 is not moved.",
            actual = moved.exceptionOrNull()?.message,
        )
        assertEquals(
            expected =
                listOf(
                    "v1 Earlier release",
                    "v1.8.0 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should fail without moving any tag when the release tag is a prerelease version`() {
        // Arrange
        repository.release("v1.10.0-rc.1")
        runner.release("v1.10.0-rc.1")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(
            expected = "::error::v1.10.0-rc.1 is not a version such as v1.2.3, so no major tag is moved.",
            actual = moved.exceptionOrNull()?.message,
        )
        assertEquals(
            expected =
                listOf(
                    "v1 Earlier release",
                    "v1.10.0-rc.1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should fail without moving any tag when the release tag has no v prefix`() {
        // Arrange
        repository.release("1.9.2")
        runner.release("1.9.2")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(
            expected = "::error::1.9.2 is not a version such as v1.2.3, so no major tag is moved.",
            actual = moved.exceptionOrNull()?.message,
        )
        assertEquals(
            expected =
                listOf(
                    "1.9.2 New release",
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }

    @Test
    fun `should fail without moving any tag when the release tag has no patch version`() {
        // Arrange
        repository.release("v1.10")
        runner.release("v1.10")
        val moveMajorTag = MoveMajorTagJob(runner.context(repository.checkout()))

        // Act
        val moved = moveMajorTag.run()

        // Assert
        assertEquals(
            expected = "::error::v1.10 is not a version such as v1.2.3, so no major tag is moved.",
            actual = moved.exceptionOrNull()?.message,
        )
        assertEquals(
            expected =
                listOf(
                    "v1 Earlier release",
                    "v1.10 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
            actual = repository.tags(),
        )
    }
}
