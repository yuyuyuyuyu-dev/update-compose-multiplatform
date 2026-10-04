#!/usr/bin/env -S kotlin -howtorun .main.kts

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    File(System.getenv("RUNNER_TEMP"), "gh-calls.jsonl").appendText("${JsonArray(arguments.map(::JsonPrimitive))}\n")
    if (System.getenv("GH_TOKEN").isNullOrEmpty() && System.getenv("GITHUB_TOKEN").isNullOrEmpty()) {
        fail(4, "gh: To use GitHub CLI in a GitHub Actions workflow, set the GH_TOKEN environment variable.")
    }
    response(arguments.toList())?.let(::println)
}

fun response(arguments: List<String>): String? {
    val command = arguments.take(2).joinToString(" ")
    return when {
        command == "api /user" -> {
            buildJsonObject { put("login", "octocat") }.toString()
        }

        command.startsWith("api /users/") -> {
            buildJsonObject {
                put("login", command.removePrefix("api /users/"))
                put("id", 1)
            }.toString()
        }

        command == "pr list" -> {
            "[]"
        }

        command == "pr create" -> {
            val repository =
                arguments
                    .zipWithNext()
                    .firstOrNull { (name) -> name == "--repo" }
                    ?.second
                    .orEmpty()
            "https://github.com/$repository/pull/1"
        }

        command == "pr merge" -> {
            null
        }

        else -> {
            fail(1, "The fake gh does not support gh ${arguments.joinToString(" ")}.")
        }
    }
}

fun fail(
    status: Int,
    message: String,
): Nothing {
    System.err.println(message)
    exitProcess(status)
}

main(args)
