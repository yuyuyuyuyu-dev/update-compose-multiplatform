package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UpdateJob(
    private val settings: Settings,
    private val workspace: Workspace,
    output: Appendable,
) {
    private val planner = Planner(settings, workspace, output)

    fun run() {
        val baseCommit = workspace.git("rev-parse", "HEAD")
        val branches = planner.branchUpdates().filter { it.units.isNotEmpty() }
        val handover =
            buildJsonObject {
                put("base", baseCommit)
                put(
                    "branches",
                    buildJsonObject {
                        branches.forEach { branch -> put(branch.name, updateBranch(baseCommit, branch)) }
                    },
                )
            }
        settings.updates.writeText(handover.toString())
    }

    private fun updateBranch(
        baseCommit: String,
        update: BranchUpdate,
    ): JsonObject {
        workspace.git("checkout", "--quiet", "--force", "--detach", baseCommit)
        workspace.file(settings.projectPath(CATALOG_PATH)).writeText(planner.catalog(update))
        val lockFiles = projectLockFiles(settings, workspace)
        if (lockFiles.isNotEmpty()) {
            workspace.gradlew(settings.directory, lockFiles.values.toList())
        }
        val files = listOf(settings.projectPath(CATALOG_PATH)) + lockFiles.keys
        return buildJsonObject { files.forEach { put(it, workspace.file(it).readText()) } }
    }
}
