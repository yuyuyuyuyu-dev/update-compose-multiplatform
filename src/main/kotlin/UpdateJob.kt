package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class UpdateJob(
    private val context: RunContext,
    private val sources: Sources,
    private val output: Appendable,
) {
    private val workspace = Workspace(context.workspace)

    fun run(
        projectDirectoryToUpdate: String? = null,
        autoMerge: String? = null,
        autoMergeUpdateTypes: String? = null,
    ): Result<Unit> =
        runningJob {
            val settings = Settings(projectDirectoryToUpdate, autoMerge, autoMergeUpdateTypes, workspace)
            val planner = Planner(settings, sources, workspace, output)
            val baseCommit = workspace.git("rev-parse", "HEAD")
            val branches = planner.branchUpdates().filter { it.units.isNotEmpty() }
            val handover =
                buildJsonObject {
                    put("base", baseCommit)
                    put(
                        "branches",
                        buildJsonObject {
                            branches.forEach { branch ->
                                put(branch.name, updateBranch(settings, planner, baseCommit, branch))
                            }
                        },
                    )
                }
            File(context.temp, HANDOVER_FILE).writeText(handover.toString())
        }

    private fun updateBranch(
        settings: Settings,
        planner: Planner,
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
