package dev.yuyuyuyuyu.updatecomposemultiplatform.specs

import dev.yuyuyuyuyu.updatecomposemultiplatform.PublishJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.Sources
import dev.yuyuyuyuyu.updatecomposemultiplatform.UpdateJob
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.AUTO_MERGE_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.CATALOG
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeChangelog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeMaven
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakePullRequest
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.MANUAL_BRANCH
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.Outcome
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.REPOSITORY
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.catalog
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.everyLibraryGroup
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.resolvedLockFile
import org.junit.jupiter.api.AutoClose
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateComposeMultiplatformSpec {
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
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
            actual = repository.file(MANUAL_BRANCH, CATALOG),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should open a pull request`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        val pullRequest = github.pullRequest(MANUAL_BRANCH)
        assertEquals(
            expected =
                FakePullRequest(
                    1,
                    MANUAL_BRANCH,
                    "main",
                    "build(deps): update io.ktor:ktor-client-core to 3.3.0",
                    pullRequest.body,
                ),
            actual = pullRequest,
        )
        assertEquals(
            expected =
                listOf(
                    "fake-app[bot] <41898282+fake-app[bot]@users.noreply.github.com> " +
                        "build(deps): update io.ktor:ktor-client-core to 3.3.0",
                ),
            actual = repository.commits(MANUAL_BRANCH),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should enable auto-merge`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "major,minor,patch")
        val published =
            publish.run(
                token = "fake-app-token",
                appSlug = "fake-app",
                autoMerge = "squash",
                autoMergeUpdateTypes = "major,minor,patch",
            )

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = "squash", actual = github.pullRequest(AUTO_MERGE_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), AUTO_MERGE_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls", "POST /graphql enablePullRequestAutoMerge"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should enable auto-merge only for the chosen update types`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "patch")
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app", autoMerge = "squash", autoMergeUpdateTypes = "patch")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = null, actual = github.pullRequest(MANUAL_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should split the updates into a pull request with auto-merge and one without`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0", okio = "3.15.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "4.0.0")
        maven.publish("com.squareup.okio:okio", "3.15.0", "3.16.0")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run(autoMerge = "squash", autoMergeUpdateTypes = "minor,patch")
        val published =
            publish.run(
                token = "fake-app-token",
                appSlug = "fake-app",
                autoMerge = "squash",
                autoMergeUpdateTypes = "minor,patch",
            )

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = catalog(ktor = "3.2.0", okio = "3.16.0"), actual = repository.file(AUTO_MERGE_BRANCH, CATALOG))
        assertEquals(expected = catalog(ktor = "4.0.0", okio = "3.15.0"), actual = repository.file(MANUAL_BRANCH, CATALOG))
        assertEquals(expected = "squash", actual = github.pullRequest(AUTO_MERGE_BRANCH).autoMerge)
        assertEquals(expected = null, actual = github.pullRequest(MANUAL_BRANCH).autoMerge)
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), AUTO_MERGE_BRANCH to setOf(CATALOG), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf(
                        "POST /repos/$REPOSITORY/pulls",
                        "POST /graphql enablePullRequestAutoMerge",
                        "POST /repos/$REPOSITORY/pulls",
                    ),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should update a prerelease to a later prerelease`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0-alpha01")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0-alpha01", "3.3.0-beta01", "3.4.0-alpha01")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = catalog(ktor = "3.3.0-beta01"), actual = repository.file(MANUAL_BRANCH, CATALOG))
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should not update a stable release to a prerelease`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.2.0")))
        maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.2.1", "3.3.0-rc01")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = catalog(ktor = "3.2.1"), actual = repository.file(MANUAL_BRANCH, CATALOG))
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should update a prerelease to its stable release`() {
        // Arrange
        repository.commit(mapOf(CATALOG to catalog(ktor = "3.3.0-rc01")))
        maven.publish("io.ktor:ktor-client-core", "3.3.0-rc01", "3.3.0", "3.4.0-alpha01")
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = catalog(ktor = "3.3.0"), actual = repository.file(MANUAL_BRANCH, CATALOG))
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run(projectDirectoryToUpdate = "example")
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app", projectDirectoryToUpdate = "example")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(expected = catalog(ktor = "3.3.0"), actual = repository.file(MANUAL_BRANCH, "example/$CATALOG"))
        assertEquals(
            expected = resolvedLockFile("kotlinUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_BRANCH, "example/kotlin-js-store/yarn.lock"),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf(
                        "main" to emptySet(),
                        MANUAL_BRANCH to setOf("example/$CATALOG", "example/kotlin-js-store/yarn.lock"),
                    ),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(
            expected = resolvedLockFile("kotlinUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_BRANCH, "kotlin-js-store/yarn.lock"),
        )
        assertEquals(
            expected = resolvedLockFile("kotlinWasmUpgradeYarnLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_BRANCH, "kotlin-js-store/wasm/yarn.lock"),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf(
                        "main" to emptySet(),
                        MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock", "kotlin-js-store/wasm/yarn.lock"),
                    ),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock"),
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(
            expected = resolvedLockFile("kotlinUpgradePackageLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_BRANCH, "kotlin-js-store/package-lock.json"),
        )
        assertEquals(
            expected = resolvedLockFile("kotlinWasmUpgradePackageLock", catalog(ktor = "3.3.0")),
            actual = repository.file(MANUAL_BRANCH, "kotlin-js-store/wasm/package-lock.json"),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf(
                        "main" to emptySet(),
                        MANUAL_BRANCH to
                            setOf(
                                CATALOG,
                                "kotlin-js-store/package-lock.json",
                                "kotlin-js-store/wasm/package-lock.json",
                            ),
                    ),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinUpgradePackageLock kotlinWasmUpgradePackageLock"),
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
        val update = UpdateJob(runner.context(repository.checkout()), sources, runner.output)
        val publish = PublishJob(runner.context(repository.checkout()), sources, github.endpoint, runner.output)

        // Act
        val updated = update.run()
        val published = publish.run(token = "fake-app-token", appSlug = "fake-app")

        // Assert
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(
            expected = everyLibraryGroup("1.10.1", "1.10.0-alpha06", "1.2.0", "2.10.0-alpha06", "2.9.2"),
            actual = repository.file(MANUAL_BRANCH, CATALOG),
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG)),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                ),
            actual = outcome(),
        )
    }

    @Test
    fun `should describe the updates in the pull request body`() {
        // Arrange
        repository.commit(
            mapOf(
                CATALOG to catalog(compose = "1.10.0", ktor = "3.2.0", okio = "3.15.0"),
                "kotlin-js-store/yarn.lock" to "Resolved before\n",
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
        assertEquals(expected = Result.success(Unit), actual = updated)
        assertEquals(expected = Result.success(Unit), actual = published)
        assertEquals(
            expected =
                """
                Updates the dependencies in `gradle/libs.versions.toml`. The Update Compose Multiplatform workflow updates this pull request on each run and closes it when it is no longer needed.

                | Dependency | Version | From | To |
                | --- | --- | --- | --- |
                | `org.jetbrains.compose` | `versions.composeMultiplatform` | `1.10.0` | `1.10.1` |
                | `io.ktor:ktor-client-core` | `versions.ktor` | `3.2.0` | `3.3.0` |
                | `com.squareup.okio:okio` | `libraries.okio` | `3.15.0` | `3.16.0` |

                - Compose Multiplatform is the latest release in the Compose Multiplatform CHANGELOG that it can move to, and the libraries paired with it are the versions that the CHANGELOG lists for that release.
                - The other dependencies are the latest versions in Maven Central, the Google Maven repository and the Gradle Plugin Portal.
                - A stable version moves only to stable versions. A prerelease moves to a later prerelease of the same version, or to a stable version.
                - The lock file `kotlin-js-store/yarn.lock` was updated with `./gradlew kotlinUpgradeYarnLock`.
                """.trimIndent() + "\n",
            actual = github.pullRequest(MANUAL_BRANCH).body,
        )
        assertEquals(
            expected =
                Outcome(
                    mapOf("main" to emptySet(), MANUAL_BRANCH to setOf(CATALOG, "kotlin-js-store/yarn.lock")),
                    listOf("POST /repos/$REPOSITORY/pulls"),
                    listOf("kotlinUpgradeYarnLock"),
                ),
            actual = outcome(),
        )
    }

    private fun outcome(): Outcome = Outcome(repository.changedFiles(), github.requests.toList(), repository.gradleCalls())
}
