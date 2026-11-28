package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PublishJob(
    private val runnerPaths: RunnerPaths,
    private val versionSources: VersionSources,
    private val endpoint: GitHubEndpoint,
    private val logDestination: Appendable,
) {
    private val workspace = Workspace(runnerPaths.workspace)

    fun run(
        token: String?,
        appSlug: String?,
        projectDirectoryToUpdate: String? = null,
        autoMerge: String? = null,
        autoMergeUpdateTypes: String? = null,
    ): Result<Unit> =
        runningJob {
            check(!token.isNullOrEmpty() && !appSlug.isNullOrEmpty()) {
                "Pass the app-client-id input and the app-private-key secret, " +
                    "so that the workflow can create a token for the GitHub App."
            }
            val settings = Settings(projectDirectoryToUpdate, autoMerge, autoMergeUpdateTypes, workspace)
            Publication(
                settings,
                Planner(settings, versionSources, workspace, logDestination),
                GitHub(endpoint, token),
                appSlug,
                eventField(runnerPaths.event, "repository", "default_branch"),
            ).publish()
        }

    private inner class Publication(
        private val settings: Settings,
        private val planner: Planner,
        private val github: GitHub,
        private val appSlug: String,
        private val base: String,
    ) {
        private val catalogPath = settings.projectPath(CATALOG_PATH)

        fun publish() {
            val handover = Json.parseToJsonElement(File(runnerPaths.temp, HANDOVER_FILE).readText()).jsonObject
            val baseCommit = workspace.git("rev-parse", "HEAD")
            val branches = planner.branchUpdates()
            val moved = handover.getValue("base").jsonPrimitive.content != baseCommit
            val files = if (moved) null else handedOverFiles(branches, handover)
            when {
                moved -> {
                    logDestination.appendLine(
                        "::warning::The default branch moved while the lock files were being updated, " +
                            "so nothing is published. The next run updates it.",
                    )
                }

                files == null -> {
                    logDestination.appendLine(
                        "::warning::The updates changed while the lock files were being updated, " +
                            "so nothing is published. The next run picks them up.",
                    )
                }

                else -> {
                    configureCommitter()
                    branches.forEach { syncBranch(baseCommit, it, files) }
                }
            }
        }

        private fun handedOverFiles(
            branches: List<BranchUpdate>,
            handover: JsonObject,
        ): Map<String, Map<String, String>>? {
            val files =
                handover.getValue("branches").jsonObject.mapValues { (_, branch) ->
                    branch.jsonObject.mapValues { it.value.jsonPrimitive.content }
                }
            val expectedPaths = setOf(catalogPath) + projectLockFiles(settings, workspace).keys
            val wrong = files.filterValues { it.keys != expectedPaths }.keys
            check(wrong.isEmpty()) {
                "The update job did not hand over just the version catalog and the lock files for ${listed(wrong.toList())}."
            }
            val planned = branches.filter { it.units.isNotEmpty() }
            val matches =
                files.keys == planned.map { it.name }.toSet() &&
                    planned.all { files.getValue(it.name)[catalogPath] == planner.catalog(it) }
            return files.takeIf { matches }
        }

        private fun syncBranch(
            baseCommit: String,
            update: BranchUpdate,
            files: Map<String, Map<String, String>>,
        ) {
            val pullRequest = github.openPullRequest(update.name, base)
            if (update.units.isEmpty()) {
                closePullRequest(update.name, pullRequest)
                return
            }
            workspace.git("checkout", "--quiet", "--force", "--detach", baseCommit)
            files.getValue(update.name).forEach { (path, content) -> workspace.file(path).writeText(content) }
            val lockFiles = projectLockFiles(settings, workspace)
            val changed =
                workspace
                    .git(listOf("diff", "--name-only", "-z", "--") + lockFiles.keys)
                    .split("\u0000")
                    .filter(String::isNotEmpty)
            val title = title(update.units)
            val command = lockFiles.takeIf { it.isNotEmpty() }?.let(::lockFileCommand)
            val body = body(catalogPath, update.units, LockFiles(command, changed))
            workspace.git(listOf("add", "--", catalogPath) + changed)
            workspace.git("commit", "--quiet", "--message", title)
            pushBranch(update.name)
            when {
                pullRequest == null -> {
                    val created = github.createPullRequest(update.name, base, title, body)
                    update.autoMerge?.let { github.setAutoMerge(created, it) }
                    logDestination.appendLine("Opened ${created.url}.")
                }

                pullRequest.title != title || pullRequest.body != body -> {
                    github.editPullRequest(pullRequest, title, body)
                    if (update.autoMerge == null && pullRequest.autoMerge != null) {
                        github.setAutoMerge(pullRequest, null)
                    }
                    logDestination.appendLine("Updated ${pullRequest.url}.")
                }

                else -> {
                    logDestination.appendLine("${pullRequest.url} already has this update.")
                }
            }
        }

        private fun closePullRequest(
            branch: String,
            pullRequest: PullRequest?,
        ) {
            if (pullRequest != null) {
                github.comment(pullRequest, "Closing because $base needs none of these updates any more.")
                github.closePullRequest(pullRequest)
                logDestination.appendLine("Closed ${pullRequest.url}.")
            }
            if (workspace.git("ls-remote", "--heads", "origin", "refs/heads/$branch").isNotEmpty()) {
                workspace.git("push", "--quiet", "origin", "--delete", "refs/heads/$branch")
            }
        }

        private fun configureCommitter() {
            val (login, id) = github.user(appSlug)
            workspace.git("config", "user.name", login)
            workspace.git("config", "user.email", "$id+$login@users.noreply.github.com")
        }

        private fun pushBranch(branch: String) {
            val holdsTree =
                workspace.git("ls-remote", "--heads", "origin", "refs/heads/$branch").isNotEmpty() &&
                    workspace.git("fetch", "--quiet", "--depth=1", "origin", "refs/heads/$branch").let {
                        workspace.git("rev-parse", "FETCH_HEAD^{tree}") == workspace.git("rev-parse", "HEAD^{tree}")
                    }
            if (holdsTree) {
                logDestination.appendLine("$branch already holds this update.")
            } else {
                workspace.git("push", "--quiet", "--force", "origin", "HEAD:refs/heads/$branch")
            }
        }
    }
}
