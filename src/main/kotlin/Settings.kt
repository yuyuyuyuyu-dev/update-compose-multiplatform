package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val autoMergeMethods = listOf("disable", "squash", "merge", "rebase")
private const val COMPOSE_CHANGELOG_URL = "https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md"
private val mavenRepositoryUrls =
    listOf(
        "https://repo.maven.apache.org/maven2",
        "https://dl.google.com/dl/android/maven2",
        "https://plugins.gradle.org/m2",
    )

class Settings(
    environment: Map<String, String>,
    workspace: Workspace,
) {
    private val inputs = inputs(environment)
    private val autoMergeUpdateTypes = commaSeparated(inputs["auto-merge-update-types"])

    val directory: String = inputs["project-directory-to-update"].orEmpty().ifEmpty { "." }
    val autoMerge: String = inputs["auto-merge"].orEmpty().ifEmpty { "disable" }
    val updates = File(required(environment, "RUNNER_TEMP"), "updates.json")
    val changelogUrl: String = environment["COMPOSE_MULTIPLATFORM_CHANGELOG"] ?: COMPOSE_CHANGELOG_URL
    val mavenRepositories: List<String> =
        (environment["MAVEN_REPOSITORIES"] ?: mavenRepositoryUrls.joinToString("\n"))
            .lines()
            .map { it.trim().removeSuffix("/") }
            .filter(String::isNotEmpty)

    init {
        val problems =
            buildList {
                if (autoMerge !in autoMergeMethods) {
                    add("auto-merge must be disable, squash, merge or rebase.")
                }
                if (autoMerge != "disable" && !updateTypes.containsAll(autoMergeUpdateTypes.ifEmpty { listOf("") })) {
                    add(
                        "auto-merge-update-types must list the update types to merge automatically, " +
                            "such as minor,patch, unless auto-merge is disable.",
                    )
                }
                if (!workspace.file(projectPath(CATALOG_PATH)).isFile) {
                    add(
                        "${projectPath(CATALOG_PATH)} does not exist. " +
                            "Pass the directory of the Compose Multiplatform project as project-directory-to-update.",
                    )
                }
            }
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }

    fun autoMerges(type: String): Boolean = autoMerge != "disable" && type in autoMergeUpdateTypes

    fun projectPath(path: String): String =
        File(directory, path)
            .toPath()
            .normalize()
            .toString()
}

class Publishing(
    environment: Map<String, String>,
) {
    val token: String = required(environment, "TOKEN")
    val appSlug: String = required(environment, "APP_SLUG")
    val base: String =
        Json
            .parseToJsonElement(File(required(environment, "GITHUB_EVENT_PATH")).readText())
            .jsonObject["repository"]
            ?.jsonObject
            ?.get("default_branch")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: error("The event of this run does not name the default branch of the repository.")
    val repository: String = required(environment, "GITHUB_REPOSITORY")
    val apiUrl: String = required(environment, "GITHUB_API_URL").removeSuffix("/")
    val graphqlUrl: String = required(environment, "GITHUB_GRAPHQL_URL")
}

private fun inputs(environment: Map<String, String>): Map<String, String> =
    Json
        .parseToJsonElement(required(environment, "INPUTS"))
        .jsonObject
        .mapValues {
            it.value.jsonPrimitive.contentOrNull
                .orEmpty()
        }

private fun commaSeparated(text: String?): List<String> =
    text
        .orEmpty()
        .split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)

fun required(
    environment: Map<String, String>,
    name: String,
): String = environment[name].orEmpty().ifEmpty { error("$name is not set.") }
