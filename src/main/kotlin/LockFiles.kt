private val lockFileTasks =
    mapOf(
        "kotlin-js-store/yarn.lock" to "kotlinUpgradeYarnLock",
        "kotlin-js-store/wasm/yarn.lock" to "kotlinWasmUpgradeYarnLock",
        "kotlin-js-store/package-lock.json" to "kotlinUpgradePackageLock",
        "kotlin-js-store/wasm/package-lock.json" to "kotlinWasmUpgradePackageLock",
    )

class LockFiles(
    val command: String?,
    val changed: List<String>,
)

fun projectLockFiles(
    settings: Settings,
    workspace: Workspace,
): Map<String, String> {
    val tracked =
        workspace
            .git("ls-files", "-z", "--", settings.projectPath("kotlin-js-store"))
            .split("\u0000")
            .toSet()
    return lockFileTasks.mapKeys { settings.projectPath(it.key) }.filterKeys { it in tracked }
}

fun lockFileCommand(lockFiles: Map<String, String>): String = (listOf("./gradlew") + lockFiles.values).joinToString(" ")
