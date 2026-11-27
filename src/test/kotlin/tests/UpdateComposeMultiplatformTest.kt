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
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.REPOSITORY
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.catalog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.everyLibraryGroup
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateComposeMultiplatformTest {
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

    private val nothingChanged = Outcome(mapOf("main" to emptySet()))

    @Test
    fun `should fail without changing anything when no token of the GitHub App is passed`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "", appSlug = "")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(1, published)
        assertEquals(
            listOf(
                "Pass the app-client-id input and the app-private-key secret, " +
                    "so that the workflow can create a token for the GitHub App.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should fail without changing anything when auto-merge is not a merge method`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run(autoMerge = "fast-forward", autoMergeUpdateTypes = "minor")

        // Assert
        assertEquals(1, updated)
        assertEquals(listOf("auto-merge must be disable, squash, merge or rebase."), runner.errors())
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should fail without changing anything when auto-merge has no valid update types`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "minor,prerelease")

        // Assert
        assertEquals(1, updated)
        assertEquals(
            listOf(
                "auto-merge-update-types must list the update types to merge automatically, " +
                    "such as minor,patch, unless auto-merge is disable.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should fail without changing anything when the project directory has no version catalog`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run(projectDirectoryToUpdate = "example")

        // Assert
        assertEquals(1, updated)
        assertEquals(
            listOf(
                "example/gradle/libs.versions.toml does not exist. " +
                    "Pass the directory of the Compose Multiplatform project as project-directory-to-update.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(1, updated)
        assertEquals(
            listOf(
                "gradle/libs.versions.toml has no org.jetbrains.compose plugin " +
                    "or compose-gradle-plugin library with a version. " +
                    "This workflow is only for projects that take Compose Multiplatform from the version catalog.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG lists no stable release`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        changelog.text = changelogNamed("no-stable-release.md")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(1, updated)
        assertEquals(listOf("The Compose Multiplatform CHANGELOG lists no stable release."), runner.errors())
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should fail without changing anything when the CHANGELOG has no version of a paired library`() {
        // Arrange
        repository.commit(
            mapOf(CATALOG to everyLibraryGroup("1.9.0", "1.9.0-beta06", "1.2.0-alpha06", "2.9.6", "2.9.0")),
        )
        changelog.text = changelogNamed("no-material3.md")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(1, updated)
        assertEquals(
            listOf(
                "The Compose Multiplatform CHANGELOG section for 1.10.1 has no Material3 version that can be read.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)

        // Act
        val updated = update.run()

        // Assert
        assertEquals(1, updated)
        assertEquals(
            listOf(
                "Material3 and Compose Multiplatform share versions.composeMultiplatform, " +
                    "but the Compose Multiplatform CHANGELOG gives them different versions: 1.10.0-alpha06 and 1.10.1.",
            ),
            runner.errors(),
        )
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should leave the paired libraries alone while Compose Multiplatform is not updated`() {
        // Arrange
        repository.commit(mapOf(CATALOG to everyLibraryGroup("1.10.1", "1.10.0-alpha06", "1.2.0", "2.9.6", "2.9.2")))
        maven.publish("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", "2.9.6", "2.10.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(nothingChanged, outcome())
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(
            everyLibraryGroup("1.11.0-beta02", "1.11.0-beta02", "1.3.0-beta02", "2.11.0-beta02", "2.10.0-beta02"),
            repository.file(MANUAL_BRANCH, CATALOG),
        )
        assertEquals(
            "build(deps): update Compose Multiplatform to 1.11.0-beta02",
            github.pullRequest(MANUAL_BRANCH).title,
        )
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("POST /repos/$REPOSITORY/pulls"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should keep a version newer than any that the repositories have`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.5.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(nothingChanged, outcome())
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertTrue(repository.file(MANUAL_BRANCH, CATALOG).contains("ktor = \"3.3.0\""))
        assertEquals("build(deps): update ktor to 3.3.0", github.pullRequest(MANUAL_BRANCH).title)
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("POST /repos/$REPOSITORY/pulls"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should leave a dependency alone and warn when no repository has it`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0", okio = "3.15.0")))
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertTrue(
            runner.output.lines().contains(
                "::warning::versions.ktor is not updated, because no repository has io.ktor:ktor-client-core.",
            ),
        )
        assertEquals(catalog(ktor = "3.2.0", okio = "3.16.0"), repository.file(MANUAL_BRANCH, CATALOG))
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("POST /repos/$REPOSITORY/pulls"),
            ),
            outcome(),
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(nothingChanged, outcome())
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(
            "build(deps): update com.google.guava:guava to 33.1.0-jre",
            github.pullRequest(MANUAL_BRANCH).title,
        )
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("POST /repos/$REPOSITORY/pulls"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should keep the formatting of the catalog`() {
        // Arrange
        repository.commit(
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
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
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
            repository.file(MANUAL_BRANCH, CATALOG),
        )
    }

    @Test
    fun `should commit nothing but the catalog and the lock files`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "README.md" to "An app\n",
                "kotlin-js-store/yarn.lock" to "Resolved before\n",
            ),
            gradlew = repository.gradlewThatAlsoWritesReadme(),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock")),
                listOf("POST /repos/$REPOSITORY/pulls"),
                listOf("kotlinUpgradeYarnLock"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should run only the Gradle tasks of the lock files that the project has`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(ktor = "3.2.0"),
                "kotlin-js-store/wasm/yarn.lock" to "Resolved before\n",
            ),
        )
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/wasm/yarn.lock")),
                listOf("POST /repos/$REPOSITORY/pulls"),
                listOf("kotlinWasmUpgradeYarnLock"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should do nothing when no dependency has an update`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(nothingChanged, outcome())
    }

    @Test
    fun `should update the open pull request instead of opening another`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        github.openPullRequest(
            MANUAL_BRANCH,
            "build(deps): update io.ktor:ktor-client-core to 3.2.1",
            "Old body",
        )
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(
            "build(deps): update io.ktor:ktor-client-core to 3.3.0",
            github.pullRequest(MANUAL_BRANCH).title,
        )
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("PATCH /repos/$REPOSITORY/pulls/1"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should leave the open pull request and its branch alone when they already have the update`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runner.context(repository.checkout()), sources, runner.output).run()
        PublishJob(
            runner.context(repository.checkout()),
            sources,
            github.endpoint,
            runner.output,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        val commits = repository.commits(MANUAL_BRANCH)
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(commits, repository.commits(MANUAL_BRANCH))
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf("POST /repos/$REPOSITORY/pulls"),
            ),
            outcome(),
        )
    }

    @Test
    fun `should close the pull request and delete its branch when no update is left`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runner.context(repository.checkout()), sources, runner.output).run()
        PublishJob(
            runner.context(repository.checkout()),
            sources,
            github.endpoint,
            runner.output,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        maven.publish("io.ktor:ktor-client-core", "3.2.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        val pullRequest = github.pullRequest(MANUAL_BRANCH)
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
            outcome(),
        )
    }

    @Test
    fun `should turn off auto-merge on a pull request without auto-merge when its updates change`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        UpdateJob(runner.context(repository.checkout()), sources, runner.output).run()
        PublishJob(
            runner.context(repository.checkout()),
            sources,
            github.endpoint,
            runner.output,
        ).run(token = "fake-app-token", appSlug = "fake-app")
        github.pullRequest(MANUAL_BRANCH).autoMerge = "squash"
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(0, updated, runner.output.toString())
        assertEquals(0, published, runner.output.toString())
        assertEquals(null, github.pullRequest(MANUAL_BRANCH).autoMerge)
        assertEquals(
            Outcome(
                mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                listOf(
                    "POST /repos/$REPOSITORY/pulls",
                    "PATCH /repos/$REPOSITORY/pulls/1",
                    "POST /graphql disablePullRequestAutoMerge",
                ),
            ),
            outcome(),
        )
    }

    private fun outcome(): Outcome = Outcome(repository.changedFiles(), github.requests.toList(), repository.gradleCalls())
}
