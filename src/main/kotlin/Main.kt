package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File
import kotlin.system.exitProcess

private val versionSources =
    VersionSources(
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
    val runnerPaths =
        RunnerPaths(
            workspace = File(environment.getValue("GITHUB_WORKSPACE")),
            temp = File(environment.getValue("RUNNER_TEMP")),
            event = File(environment.getValue("GITHUB_EVENT_PATH")),
        )
    val result =
        when (arguments.single()) {
            "update" -> {
                UpdateJob(runnerPaths = runnerPaths, versionSources = versionSources, logDestination = System.out).run(
                    projectDirectoryToUpdate = environment["PROJECT_DIRECTORY_TO_UPDATE"],
                    autoMerge = environment["AUTO_MERGE"],
                    autoMergeUpdateTypes = environment["AUTO_MERGE_UPDATE_TYPES"],
                )
            }

            "publish" -> {
                val endpoint =
                    GitHubEndpoint(
                        apiUrl = environment.getValue("GITHUB_API_URL"),
                        graphqlUrl = environment.getValue("GITHUB_GRAPHQL_URL"),
                        repository = environment.getValue("GITHUB_REPOSITORY"),
                    )
                PublishJob(
                    runnerPaths = runnerPaths,
                    versionSources = versionSources,
                    endpoint = endpoint,
                    logDestination = System.out,
                ).run(
                    token = environment["TOKEN"],
                    appSlug = environment["APP_SLUG"],
                    projectDirectoryToUpdate = environment["PROJECT_DIRECTORY_TO_UPDATE"],
                    autoMerge = environment["AUTO_MERGE"],
                    autoMergeUpdateTypes = environment["AUTO_MERGE_UPDATE_TYPES"],
                )
            }

            "move-major-tag" -> {
                MoveMajorTagJob(runnerPaths = runnerPaths).run()
            }

            else -> {
                error("Pass update, publish or move-major-tag.")
            }
        }
    result.onFailure { println(it.message) }
    exitProcess(if (result.isSuccess) 0 else 1)
}
