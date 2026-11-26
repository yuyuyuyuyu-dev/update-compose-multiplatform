package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.MANUAL_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.REPOSITORY
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.TestWorld
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.catalog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.changelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.everyLibraryGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateComposeMultiplatformTest {
    private val nothingChanged = Outcome(mapOf("main" to emptySet()))

    @Test
    fun `should fail without changing anything when auto-merge is not a merge method`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.inputs("auto-merge" to "fast-forward", "auto-merge-update-types" to "minor")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed("auto-merge must be disable, squash, merge or rebase.")
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when auto-merge has no valid update types`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.inputs("auto-merge" to "squash", "auto-merge-update-types" to "minor,prerelease")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed(
                "auto-merge-update-types must list the update types to merge automatically, " +
                    "such as minor,patch, unless auto-merge is disable.",
            )
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when the project directory has no version catalog`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.inputs("project-directory-to-update" to "example")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed(
                "example/gradle/libs.versions.toml does not exist. " +
                    "Pass the directory of the Compose Multiplatform project as project-directory-to-update.",
            )
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when the catalog has no Compose Multiplatform`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
                mapOf(
                    CATALOG to
                        """
                        [libraries]
                        okio = "com.squareup.okio:okio:3.15.0"
                        """.trimIndent() + "\n",
                ),
            )
            world.maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed(
                "gradle/libs.versions.toml has no org.jetbrains.compose plugin " +
                    "or compose-gradle-plugin library with a version. " +
                    "This workflow is only for projects that take Compose Multiplatform from the version catalog.",
            )
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG lists no stable release`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.changelog = changelog("no-stable-release.md")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed("The Compose Multiplatform CHANGELOG lists no stable release.")
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG has no version of a paired library`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
                mapOf(CATALOG to everyLibraryGroup("1.9.0", "1.9.0-beta06", "1.2.0-alpha06", "2.9.6", "2.9.0")),
            )
            world.changelog = changelog("no-material3.md")

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed(
                "The Compose Multiplatform CHANGELOG section for 1.10.1 has no Material3 version that can be read.",
            )
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should fail without changing anything when a version key is paired with different versions`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
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

            // Act
            val updated = world.update()

            // Assert
            updated.assertFailed(
                "Material3 and Compose Multiplatform share versions.composeMultiplatform, " +
                    "but the Compose Multiplatform CHANGELOG gives them different versions: 1.10.0-alpha06 and 1.10.1.",
            )
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should leave the paired libraries alone while Compose Multiplatform is not updated`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to everyLibraryGroup("1.10.1", "1.10.0-alpha06", "1.2.0", "2.9.6", "2.9.2")))
            world.maven.publish("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", "2.9.6", "2.10.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should update a Compose Multiplatform prerelease to the latest prerelease of its version`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
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
            world.changelog = changelog("second-beta.md")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                everyLibraryGroup("1.11.0-beta02", "1.11.0-beta02", "1.3.0-beta02", "2.11.0-beta02", "2.10.0-beta02"),
                world.file(MANUAL_BRANCH, CATALOG),
            )
            assertEquals(
                "build(deps): update Compose Multiplatform to 1.11.0-beta02",
                world.github.pullRequest(MANUAL_BRANCH).title,
            )
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should keep a version newer than any that the repositories have`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.5.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should choose a version that every module sharing the version key has`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
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
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
            world.maven.publish("io.ktor:ktor-client-cio", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertTrue(world.file(MANUAL_BRANCH, CATALOG).contains("ktor = \"3.3.0\""))
            assertEquals("build(deps): update ktor to 3.3.0", world.github.pullRequest(MANUAL_BRANCH).title)
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should leave a dependency alone and warn when no repository has it`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0", okio = "3.15.0")))
            world.maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertTrue(
                updated.output.lines().contains(
                    "::warning::versions.ktor is not updated, because no repository has io.ktor:ktor-client-core.",
                ),
            )
            assertEquals(catalog(ktor = "3.2.0", okio = "3.16.0"), world.file(MANUAL_BRANCH, CATALOG))
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should leave dynamic, rich and missing versions alone`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
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
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
            world.maven.publish("com.squareup.okio:okio-fakefilesystem", "3.15.0", "3.16.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should keep the flavor of a version`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
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
            world.maven.publish(
                "com.google.guava:guava",
                "33.0.0-jre",
                "33.0.0-android",
                "33.1.0-jre",
                "33.1.0-android",
                "33.2.0-android",
            )

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                "build(deps): update com.google.guava:guava to 33.1.0-jre",
                world.github.pullRequest(MANUAL_BRANCH).title,
            )
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should keep the formatting of the catalog`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
                mapOf(
                    CATALOG to
                        """
                        [versions]
                        composeMultiplatform="1.10.1"
                        ktor   =   "3.2.0"


                        [libraries]
                        ktor-client-core = {module="io.ktor:ktor-client-core",version.ref="ktor"}
                        okio={ module = "com.squareup.okio:okio", version = "3.15.0" }

                        [plugins]
                        composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                        """.trimIndent() + "\n",
                ),
            )
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                """
                [versions]
                composeMultiplatform="1.10.1"
                ktor   =   "3.3.0"


                [libraries]
                ktor-client-core = {module="io.ktor:ktor-client-core",version.ref="ktor"}
                okio={ module = "com.squareup.okio:okio", version = "3.16.0" }

                [plugins]
                composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
                """.trimIndent() + "\n",
                world.file(MANUAL_BRANCH, CATALOG),
            )
        }
    }

    @Test
    fun `should commit nothing but the catalog and the lock files`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
                mapOf(
                    CATALOG to catalog(ktor = "3.2.0"),
                    "README.md" to "An app\n",
                    "kotlin-js-store/yarn.lock" to "Resolved before\n",
                ),
            )
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.gradlewAlsoWrites = "README.md"

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock")),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinUpgradeYarnLock"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should run only the Gradle tasks of the lock files that the project has`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(
                mapOf(
                    CATALOG to catalog(ktor = "3.2.0"),
                    "kotlin-js-store/wasm/yarn.lock" to "Resolved before\n",
                ),
            )
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/wasm/yarn.lock")),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinWasmUpgradeYarnLock"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should do nothing when no dependency has an update`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.3.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(nothingChanged, world.outcome())
        }
    }

    @Test
    fun `should update the open pull request instead of opening another`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.github.openPullRequest(
                MANUAL_BRANCH,
                "build(deps): update io.ktor:ktor-client-core to 3.2.1",
                "Old body",
            )

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(
                "build(deps): update io.ktor:ktor-client-core to 3.3.0",
                world.github.pullRequest(MANUAL_BRANCH).title,
            )
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("PATCH /repos/$REPOSITORY/pulls/1"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should leave the open pull request and its branch alone when they already have the update`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.update().assertSucceeded()
            world.publish().assertSucceeded()
            val commits = world.commits(MANUAL_BRANCH)

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(commits, world.commits(MANUAL_BRANCH))
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should close the pull request and delete its branch when no update is left`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.update().assertSucceeded()
            world.publish().assertSucceeded()
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            val pullRequest = world.github.pullRequest(MANUAL_BRANCH)
            assertEquals("closed", pullRequest.state)
            assertEquals(listOf("Closing because main needs none of these updates any more."), pullRequest.comments)
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet()),
                    listOf(
                        "POST /repos/$REPOSITORY/pulls",
                        "POST /repos/$REPOSITORY/issues/1/comments",
                        "PATCH /repos/$REPOSITORY/pulls/1",
                    ),
                ),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should turn off auto-merge on a pull request without auto-merge when its updates change`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
            world.update().assertSucceeded()
            world.publish().assertSucceeded()
            world.github.pullRequest(MANUAL_BRANCH).autoMerge = "squash"
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")

            // Act
            val updated = world.update()
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertEquals(null, world.github.pullRequest(MANUAL_BRANCH).autoMerge)
            assertEquals(
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf(
                        "POST /repos/$REPOSITORY/pulls",
                        "PATCH /repos/$REPOSITORY/pulls/1",
                        "POST /graphql disablePullRequestAutoMerge",
                    ),
                ),
                world.outcome(),
            )
        }
    }
}
