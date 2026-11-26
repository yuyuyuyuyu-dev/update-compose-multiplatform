fun title(units: List<UpdateUnit>): String {
    val first = units.first()
    return when (val others = units.size - 1) {
        0 -> "build(deps): update ${first.name} to ${first.to}"
        1 -> "build(deps): update ${first.name} and 1 other dependency"
        else -> "build(deps): update ${first.name} and $others other dependencies"
    }
}

fun body(
    catalog: String,
    units: List<UpdateUnit>,
    lockFiles: LockFiles,
): String =
    buildString {
        append("Updates the dependencies in `$catalog`. ")
        append("The Update Compose Multiplatform workflow updates this pull request on each run ")
        appendLine("and closes it when it is no longer needed.")
        appendLine()
        appendLine("| Dependency | Version | From | To |")
        appendLine("| --- | --- | --- | --- |")
        units.flatMap { it.changes }.forEach { change ->
            val dependents = change.slot.modules.joinToString(", ") { "`${it.coordinates}`" }
            appendLine("| $dependents | `${change.slot.label}` | `${change.slot.current}` | `${change.to}` |")
        }
        appendLine()
        if (units.any { it.name == COMPOSE_NAME }) {
            append("- Compose Multiplatform is the latest release in the Compose Multiplatform CHANGELOG ")
            append("that it can move to, and the libraries paired with it are the versions ")
            appendLine("that the CHANGELOG lists for that release.")
        }
        if (units.any { it.name != COMPOSE_NAME }) {
            append("- The other dependencies are the latest versions in Maven Central, ")
            appendLine("the Google Maven repository and the Gradle Plugin Portal.")
        }
        append("- A stable version moves only to stable versions. ")
        appendLine("A prerelease moves to a later prerelease of the same version, or to a stable version.")
        lockFiles.command?.let { appendLine(lockFileLine(it, lockFiles.changed)) }
    }

private fun lockFileLine(
    command: String,
    changed: List<String>,
): String {
    val files = listed(changed.map { "`$it`" })
    return when (changed.size) {
        0 -> "- `$command` changed no lock file."
        1 -> "- The lock file $files was updated with `$command`."
        else -> "- The lock files $files were updated with `$command`."
    }
}

fun listed(items: List<String>): String =
    if (items.size < 2) items.joinToString() else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"
