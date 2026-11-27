package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

private val sources =
    Sources(
        changelogUrl = "https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md",
        mavenRepositoryUrls =
            listOf(
                "https://repo.maven.apache.org/maven2",
                "https://dl.google.com/dl/android/maven2",
                "https://plugins.gradle.org/m2",
            ),
    )

fun main(arguments: Array<String>) {
    val environment = System.getenv()
    val context =
        RunContext(
            workspace = File(environment.getValue("GITHUB_WORKSPACE")),
            temp = File(environment.getValue("RUNNER_TEMP")),
            event = File(environment.getValue("GITHUB_EVENT_PATH")),
        )
    val inputs = environment["INPUTS"]?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()

    fun input(name: String): String? = inputs[name]?.jsonPrimitive?.contentOrNull
    val result =
        when (arguments.single()) {
            "update" -> {
                UpdateJob(context = context, sources = sources, output = System.out).run(
                    projectDirectoryToUpdate = input("project-directory-to-update"),
                    autoMerge = input("auto-merge"),
                    autoMergeUpdateTypes = input("auto-merge-update-types"),
                )
            }

            "publish" -> {
                val endpoint =
                    GitHubEndpoint(
                        apiUrl = environment.getValue("GITHUB_API_URL"),
                        graphqlUrl = environment.getValue("GITHUB_GRAPHQL_URL"),
                        repository = environment.getValue("GITHUB_REPOSITORY"),
                    )
                PublishJob(context = context, sources = sources, endpoint = endpoint, output = System.out).run(
                    token = environment["TOKEN"],
                    appSlug = environment["APP_SLUG"],
                    projectDirectoryToUpdate = input("project-directory-to-update"),
                    autoMerge = input("auto-merge"),
                    autoMergeUpdateTypes = input("auto-merge-update-types"),
                )
            }

            "move-major-tag" -> {
                MoveMajorTagJob(context = context).run()
            }

            else -> {
                error("Pass update, publish or move-major-tag.")
            }
        }
    result.onFailure { println(it.message) }
    exitProcess(if (result.isSuccess) 0 else 1)
}
