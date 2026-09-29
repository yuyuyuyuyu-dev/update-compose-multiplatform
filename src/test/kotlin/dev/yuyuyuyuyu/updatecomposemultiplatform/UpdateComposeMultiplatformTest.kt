package dev.yuyuyuyuyu.updatecomposemultiplatform

import org.junit.jupiter.api.io.TempDir
import org.tomlj.Toml
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class UpdateComposeMultiplatformTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `should update Compose Multiplatform along with the material3 and Material3 Adaptive paired with it`() {
        // Arrange
        val git = Git(directory)
        val repository = Repository(directory.resolve("repository.git"), git)
        val commit = repository.push("gradle/libs.versions.toml", outdatedCatalog)
        val gh = FakeGh(directory.resolve("gh"))
        val runner = Runner(directory.resolve("runner"), git, gh)

        // Act
        val update = runner.temp.resolve("update-compose-multiplatform").path
        runner.run(
            runner.checkOut(repository, "main"),
            "prepare",
            mapOf(
                "update-js-yarn-lock" to "false",
                "update-wasm-yarn-lock" to "false",
                "update-directory" to update,
                "github-output" to runner.output().path,
            ),
        )
        runner.run(
            runner.checkOut(repository, commit),
            "sync-pull-request",
            mapOf(
                "repository" to "octo-org/octo-app",
                "base" to "main",
                "branch" to "chore/update-compose-multiplatform",
                "update-directory" to update,
                "app-slug" to "compose-updater",
                "auto-merge" to "disable",
                "labels" to "",
            ),
        )

        // Assert
        val compose = latestStableComposeMultiplatform()
        val (material3, adaptive) = versionsPairedWith(compose)
        assertEquals(
            mapOf(
                "composeMultiplatform" to compose,
                "material3" to material3,
                "compose-multiplatform-adaptive" to adaptive,
            ),
            versionsIn(repository.file("chore/update-compose-multiplatform", "gradle/libs.versions.toml")),
        )
        assertEquals(
            listOf(
                PullRequest(
                    repository = "octo-org/octo-app",
                    head = "chore/update-compose-multiplatform",
                    base = "main",
                    title =
                        "build(deps): update Compose Multiplatform to $compose, " +
                            "material3 to $material3 and Material3 Adaptive to $adaptive",
                ),
            ),
            gh.pullRequests(),
        )
    }
}

private val outdatedCatalog =
    """
    [versions]
    composeMultiplatform = "1.9.0"
    material3 = "1.9.0-beta06"
    compose-multiplatform-adaptive = "1.2.0-alpha06"

    [libraries]
    compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "material3" }
    compose-material3-adaptive = { module = "org.jetbrains.compose.material3.adaptive:adaptive", version.ref = "compose-multiplatform-adaptive" }

    [plugins]
    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
    """.trimIndent() + "\n"

private fun versionsIn(catalog: String): Map<String, String> {
    val versions = Toml.parse(catalog).getTable("versions") ?: fail("The catalog has no versions table.")
    return versions.keySet().associateWith { versions.getString(listOf(it)).orEmpty() }
}

private fun fetch(url: String): String = URI(url).toURL().readText()

private fun latestStableComposeMultiplatform(): String {
    val metadata =
        fetch("https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml")
    return Regex("""<version>(\d+)\.(\d+)\.(\d+)</version>""")
        .findAll(metadata)
        .map { match -> match.groupValues.drop(1).map(String::toInt) }
        .maxWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        .joinToString(".")
}

private fun versionsPairedWith(compose: String): Pair<String, String> {
    val lines = fetch("https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md").lines()
    val heading = lines.indexOfFirst { it.startsWith("# $compose (") }
    if (heading < 0) {
        fail("The Compose Multiplatform CHANGELOG has no section for $compose yet.")
    }
    val section = lines.drop(heading + 1).takeWhile { !it.startsWith("# ") }
    return coordinateIn(section, "org.jetbrains.compose.material3:material3*") to
        coordinateIn(section, "org.jetbrains.compose.material3.adaptive:adaptive*")
}

private fun coordinateIn(
    section: List<String>,
    artifacts: String,
): String {
    val coordinate = Regex("""${Regex.escape(artifacts)}:([^`\s|]+)""")
    return section.firstNotNullOfOrNull { coordinate.find(it)?.groupValues?.get(1) }
        ?: fail("The Compose Multiplatform CHANGELOG section has no version of $artifacts.")
}
