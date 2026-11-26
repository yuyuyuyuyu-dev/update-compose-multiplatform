const val COMPOSE_NAME = "Compose Multiplatform"
private const val COMPOSE_PLUGIN_ID = "org.jetbrains.compose"
private const val MANUAL_BRANCH = "chore/update-dependencies"
private const val AUTO_MERGE_BRANCH = "chore/update-dependencies-auto-merge"
private val composeModules =
    listOf(
        "org.jetbrains.compose:compose-gradle-plugin",
        "org.jetbrains.compose.components:components-*",
    )
private val pinnedLibraries =
    setOf(
        "org.jetbrains.compose.material:material-icons-core",
        "org.jetbrains.compose.material:material-icons-extended",
    )

class UpdateUnit(
    val name: String,
    val from: Version,
    val to: Version,
    val changes: List<Change>,
) {
    val type: String = from.updateType(to)
}

class Change(
    val slot: Slot,
    val to: Version,
)

class BranchUpdate(
    val name: String,
    val units: List<UpdateUnit>,
    val autoMerge: String?,
)

class Planner(
    private val settings: Settings,
    private val workspace: Workspace,
    private val output: Appendable,
) {
    fun branchUpdates(): List<BranchUpdate> {
        val units = plan()
        val autoMerged = units.filter { settings.autoMerges(it.type) }
        return listOf(
            BranchUpdate(AUTO_MERGE_BRANCH, autoMerged, settings.autoMerge),
            BranchUpdate(MANUAL_BRANCH, units - autoMerged.toSet(), null),
        )
    }

    fun catalog(update: BranchUpdate): String {
        val catalog = versionCatalog()
        update.units.flatMap { it.changes }.forEach { catalog.set(it.slot, it.to) }
        return catalog.text()
    }

    private fun versionCatalog(): VersionCatalog = VersionCatalog(workspace.file(settings.projectPath(CATALOG_PATH)))

    private fun plan(): List<UpdateUnit> {
        val catalog = versionCatalog()
        val changelog = Changelog(fetch(settings.changelogUrl))
        val groups = changelog.libraryGroups()
        val composeSlots = catalog.slots.filter { slot -> slot.modules.any { composeKind(it, groups) != null } }
        val composeUnit = composeUnit(composeSlots, changelog, groups)
        val otherUnits = otherUnits(catalog.slots - composeSlots.toSet())
        return listOfNotNull(composeUnit) + otherUnits
    }

    private fun composeUnit(
        slots: List<Slot>,
        changelog: Changelog,
        groups: List<LibraryGroup>,
    ): UpdateUnit? {
        val composeVersions =
            slots
                .filter { slot -> slot.modules.any { composeKind(it, groups) == COMPOSE_NAME } }
                .map { it.current }
                .distinct()
        check(composeVersions.isNotEmpty()) {
            "${settings.projectPath(CATALOG_PATH)} has no org.jetbrains.compose plugin " +
                "or compose-gradle-plugin library with a version. " +
                "This workflow is only for projects that take Compose Multiplatform from the version catalog."
        }
        check(composeVersions.size == 1) {
            "Compose Multiplatform must have one version in the catalog, " +
                "but has ${listed(composeVersions.map(Version::toString))}."
        }
        val current = composeVersions.single()
        val release = current.newest(changelog.releases())
        if (release == current) {
            return null
        }
        val components = changelog.components(release)
        val changes =
            slots.map { slot ->
                val kinds = slot.modules.mapNotNull { composeKind(it, groups) }.distinct()
                val targets = kinds.map { components.version(it, groups) }
                check(targets.distinct().size == 1) {
                    "${listed(kinds)} share ${slot.label}, " +
                        "but the Compose Multiplatform CHANGELOG gives them different versions: " +
                        "${listed(targets.map(Version::toString))}."
                }
                Change(slot, targets.first())
            }
        return UpdateUnit(COMPOSE_NAME, current, release, changes.filter { it.slot.current != it.to })
    }

    private fun otherUnits(slots: List<Slot>): List<UpdateUnit> {
        val versions = MavenRepositories(settings.mavenRepositories).versions(slots.flatMap { it.modules }.distinct())
        return slots.mapNotNull { slot ->
            val missing = slot.modules.filter { versions.getValue(it).isEmpty() }
            if (missing.isNotEmpty()) {
                val coordinates = listed(missing.map { it.coordinates })
                output.appendLine("::warning::${slot.label} is not updated, because no repository has $coordinates.")
                null
            } else {
                val available = slot.modules.map { versions.getValue(it) }.reduce { common, next -> common intersect next }
                val target = slot.current.newest(available)
                if (target == slot.current) {
                    null
                } else {
                    UpdateUnit(slot.name, slot.current, target, listOf(Change(slot, target)))
                }
            }
        }
    }
}

private fun composeKind(
    module: Module,
    groups: List<LibraryGroup>,
): String? =
    when {
        module.isPlugin -> if (module.coordinates == COMPOSE_PLUGIN_ID) COMPOSE_NAME else null
        composeModules.any { matches(module.coordinates, it) } -> COMPOSE_NAME
        module.coordinates in pinnedLibraries -> null
        else -> groups.firstOrNull { matches(module.coordinates, it.coordinates) }?.name
    }

private fun matches(
    coordinates: String,
    pattern: String,
): Boolean = if (pattern.endsWith("*")) coordinates.startsWith(pattern.dropLast(1)) else coordinates == pattern
