package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

private val sources =
    Sources(
        "https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md",
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
            File(environment.getValue("GITHUB_WORKSPACE")),
            File(environment.getValue("RUNNER_TEMP")),
            File(environment.getValue("GITHUB_EVENT_PATH")),
        )
    val inputs = environment["INPUTS"]?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()

    fun input(name: String): String? = inputs[name]?.jsonPrimitive?.contentOrNull
    val exitCode =
        when (arguments.single()) {
            "update" -> {
                UpdateJob(context, sources, System.out)
                    .run(input("project-directory-to-update"), input("auto-merge"), input("auto-merge-update-types"))
            }

            "publish" -> {
                val endpoint =
                    GitHubEndpoint(
                        environment.getValue("GITHUB_API_URL"),
                        environment.getValue("GITHUB_GRAPHQL_URL"),
                        environment.getValue("GITHUB_REPOSITORY"),
                    )
                PublishJob(context, sources, endpoint, System.out).run(
                    environment["TOKEN"],
                    environment["APP_SLUG"],
                    input("project-directory-to-update"),
                    input("auto-merge"),
                    input("auto-merge-update-types"),
                )
            }

            "move-major-tag" -> {
                MoveMajorTagJob(context, System.out).run()
            }

            else -> {
                error("Pass update, publish or move-major-tag.")
            }
        }
    exitProcess(exitCode)
}
