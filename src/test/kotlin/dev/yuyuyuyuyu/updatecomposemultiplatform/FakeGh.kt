package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

class FakeGh(
    directory: File,
) {
    val bin: File = directory.resolve("bin")
    private val state: File = directory.resolve("pull-requests.json")

    init {
        bin.mkdirs()
        state.writeText("[]")
        val java = File(System.getProperty("java.home"), "bin/java")
        val classpath = System.getProperty("java.class.path")
        bin.resolve("gh").apply {
            writeText("#!/bin/sh\nexec '$java' -cp '$classpath' ${FakeGh::class.java.name} '$state' \"\$@\"\n")
            setExecutable(true)
        }
    }

    fun pullRequests(): List<PullRequest> =
        read(state).map {
            PullRequest(it.text("repository"), it.text("headRefName"), it.text("baseRefName"), it.text("title"))
        }

    companion object {
        @JvmStatic
        fun main(arguments: Array<String>) {
            val state = File(arguments.first())
            val command = arguments.drop(1)
            val output = respond(state, command)
            if (output == null) {
                System.err.println("The fake gh does not support gh ${command.joinToString(" ")}.")
                exitProcess(1)
            }
            println(output)
        }

        private fun respond(
            state: File,
            command: List<String>,
        ): String? {
            val subcommand = command.take(2)
            val options = command.drop(2).chunked(2).associate { it.first() to it.last() }
            return when {
                subcommand.first() == "api" && subcommand.last().startsWith("/users/") && options.isEmpty() -> {
                    user(subcommand.last().removePrefix("/users/"))
                }

                subcommand == listOf("pr", "list") -> {
                    list(state, options)
                }

                subcommand == listOf("pr", "create") -> {
                    create(state, options)
                }

                else -> {
                    null
                }
            }
        }

        private fun user(login: String): String =
            buildJsonObject {
                put("login", login)
                put("id", 1)
            }.toString()

        private fun list(
            state: File,
            options: Map<String, String>,
        ): String? {
            val expected = setOf("--repo", "--head", "--base", "--state", "--json")
            if (options.keys != expected || options["--state"] != "open") {
                return null
            }
            val fields = options.getValue("--json").split(",")
            val matching =
                read(state).filter {
                    it.text("repository") == options["--repo"] &&
                        it.text("headRefName") == options["--head"] &&
                        it.text("baseRefName") == options["--base"]
                }
            val selected = matching.map { pullRequest -> JsonObject(fields.associateWith { pullRequest.getValue(it) }) }
            return JsonArray(selected).toString()
        }

        private fun create(
            state: File,
            options: Map<String, String>,
        ): String? {
            if (options.keys != setOf("--repo", "--head", "--base", "--title", "--body-file")) {
                return null
            }
            val pullRequests = read(state)
            val number = pullRequests.size + 1
            val repository = options.getValue("--repo")
            val url = "https://github.com/$repository/pull/$number"
            val pullRequest =
                buildJsonObject {
                    put("repository", repository)
                    put("number", number)
                    put("url", url)
                    put("headRefName", options.getValue("--head"))
                    put("baseRefName", options.getValue("--base"))
                    put("title", options.getValue("--title"))
                    put("body", File(options.getValue("--body-file")).readText())
                }
            state.writeText(JsonArray(pullRequests + pullRequest).toString())
            return url
        }
    }
}

data class PullRequest(
    val repository: String,
    val head: String,
    val base: String,
    val title: String,
)

private fun read(state: File): List<JsonObject> =
    Json
        .parseToJsonElement(state.readText())
        .jsonArray
        .map { it.jsonObject }

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
