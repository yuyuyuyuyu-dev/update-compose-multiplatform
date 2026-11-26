package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.support.ReleaseWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveMajorTagTest {
    @Test
    fun `should move the major tag to a new release of the same major version`() {
        // Arrange
        ReleaseWorld("v1.9.2").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertSucceeded()
            assertEquals(
                listOf(
                    "v1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                    "v1.9.2 New release",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should ignore prereleases when it looks for a newer release`() {
        // Arrange
        ReleaseWorld("v1.9.1").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertSucceeded()
            assertEquals(
                listOf(
                    "v1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1 New release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should treat minor version 10 as newer than minor version 9`() {
        // Arrange
        ReleaseWorld("v1.10.0").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertSucceeded()
            assertEquals(
                listOf(
                    "v1 New release",
                    "v1.10.0 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should create the major tag for the first release of a major version`() {
        // Arrange
        ReleaseWorld("v2.0.0").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertSucceeded()
            assertEquals(
                listOf(
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                    "v2 New release",
                    "v2.0.0 New release",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should ignore other major versions when it looks for a newer release`() {
        // Arrange
        ReleaseWorld("v0.9.1").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertSucceeded()
            assertEquals(
                listOf(
                    "v0 New release",
                    "v0.9.1 New release",
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should fail without moving any tag when the same major version has a newer release`() {
        // Arrange
        ReleaseWorld("v1.8.0").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertFailed("v1.8.0 is older than v1.9.0, so v1 is not moved.")
            assertEquals(
                listOf(
                    "v1 Earlier release",
                    "v1.8.0 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should fail without moving any tag when the release tag is a prerelease version`() {
        // Arrange
        ReleaseWorld("v1.10.0-rc.1").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertFailed("v1.10.0-rc.1 is not a version such as v1.2.3, so no major tag is moved.")
            assertEquals(
                listOf(
                    "v1 Earlier release",
                    "v1.10.0-rc.1 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should fail without moving any tag when the release tag has no v prefix`() {
        // Arrange
        ReleaseWorld("1.9.2").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertFailed("1.9.2 is not a version such as v1.2.3, so no major tag is moved.")
            assertEquals(
                listOf(
                    "1.9.2 New release",
                    "v1 Earlier release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }

    @Test
    fun `should fail without moving any tag when the release tag has no patch version`() {
        // Arrange
        ReleaseWorld("v1.10").use { world ->
            // Act
            val result = world.moveMajorTag()

            // Assert
            result.assertFailed("v1.10 is not a version such as v1.2.3, so no major tag is moved.")
            assertEquals(
                listOf(
                    "v1 Earlier release",
                    "v1.10 New release",
                    "v1.9.0 Earlier release",
                    "v1.9.1-rc.1 Prerelease",
                ),
                world.tags(),
            )
        }
    }
}
