package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

private val autoMergeMethods = listOf("disable", "squash", "merge", "rebase")

class Settings(
    projectDirectoryToUpdate: String?,
    autoMerge: String?,
    autoMergeUpdateTypes: String?,
    workspace: Workspace,
) {
    private val autoMergeUpdateTypes = commaSeparated(autoMergeUpdateTypes)

    val directory: String = projectDirectoryToUpdate.orEmpty().ifEmpty { "." }
    val autoMerge: String = autoMerge.orEmpty().ifEmpty { "disable" }

    init {
        val problems =
            buildList {
                if (this@Settings.autoMerge !in autoMergeMethods) {
                    add("auto-merge must be disable, squash, merge or rebase.")
                }
                if (this@Settings.autoMerge != "disable" &&
                    !updateTypes.containsAll(this@Settings.autoMergeUpdateTypes.ifEmpty { listOf("") })
                ) {
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

fun eventField(
    event: File,
    vararg path: String,
): String {
    val root: JsonElement = Json.parseToJsonElement(event.readText())
    val value = path.fold<String, JsonElement?>(root) { element, name -> (element as? JsonObject)?.get(name) }
    return (value as? JsonPrimitive)?.contentOrNull
        ?: error("The event of this run has no ${path.joinToString(".")}.")
}

private fun commaSeparated(text: String?): List<String> =
    text
        .orEmpty()
        .split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
