@file:DependsOn("org.tomlj:tomlj:2.2.0")
@file:DependsOn("org.checkerframework:checker-qual:4.4.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.13.0")

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

val composeName = "Compose Multiplatform"
val composePluginId = "org.jetbrains.compose"
val composeModules =
    listOf(
        "org.jetbrains.compose:compose-gradle-plugin",
        "org.jetbrains.compose.components:components-*",
    )
val pinnedLibraries =
    setOf(
        "org.jetbrains.compose.material:material-icons-core",
        "org.jetbrains.compose.material:material-icons-extended",
    )
val catalogPath = "gradle/libs.versions.toml"
val lockFileTasks =
    mapOf(
        "kotlin-js-store/yarn.lock" to "kotlinUpgradeYarnLock",
        "kotlin-js-store/wasm/yarn.lock" to "kotlinWasmUpgradeYarnLock",
        "kotlin-js-store/package-lock.json" to "kotlinUpgradePackageLock",
        "kotlin-js-store/wasm/package-lock.json" to "kotlinWasmUpgradePackageLock",
    )
val manualBranch = "chore/update-dependencies"
val autoMergeBranch = "chore/update-dependencies-auto-merge"
val autoMergeMethods = listOf("disable", "squash", "merge", "rebase")
val updateTypes = listOf("major", "minor", "patch")
val prereleaseWords =
    setOf("alpha", "beta", "rc", "cr", "m", "milestone", "dev", "eap", "ea", "preview", "pre", "snapshot")
val wordOrder = listOf("dev", "", "rc", "snapshot", "final", "ga", "release", "sp")
val versionParts = Regex("""\d+|[^\d.\-_+]+""")
val dynamicVersion = Regex("""[+\[\](),]|^latest\.""")
val gradlePluginRow = Regex("""`org\.jetbrains\.compose` version `([^`]+)`""")
val libraryRow = Regex("""\|\s*([^|`]*?)\s*\|\s*`([^`:\s]+):([^`:\s]+):([^`\s]+)`\s*\|.*""")
val componentsHeading = Regex("""##\s+Components\s*""")
val secondLevelHeading = Regex("""##\s.*""")
val releaseHeading = Regex("""#+\s+(\d+\.\d+\.\d+\S*)\s+\(.*""")
val metadataVersion = Regex("""<version>\s*([^<\s]+)\s*</version>""")
val client: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

fun main() {
    try {
        val settings = Settings(System.getenv())
        val github = GitHub(settings)
        val units = plan(settings)
        val baseCommit = git("rev-parse", "HEAD")
        configureCommitter(github, settings)
        val autoMerged = units.filter { settings.autoMerges(it.type) }
        val branches =
            listOf(
                BranchUpdate(autoMergeBranch, autoMerged, settings.autoMerge),
                BranchUpdate(manualBranch, units - autoMerged.toSet(), null),
            )
        branches.forEach { syncBranch(settings, github, baseCommit, it) }
    } catch (failure: IllegalStateException) {
        failure.message
            .orEmpty()
            .lines()
            .forEach { println("::error::$it") }
        exitProcess(1)
    }
}

class Settings(
    environment: Map<String, String>,
) {
    private val token = environment["TOKEN"].orEmpty()
    private val appToken = environment["APP_TOKEN"].orEmpty()
    private val autoMergeUpdateTypes = commaSeparated(environment["AUTO_MERGE_UPDATE_TYPES"])

    val directory: String = environment["PROJECT_DIRECTORY"].orEmpty().ifEmpty { "." }
    val autoMerge: String = environment["AUTO_MERGE"].orEmpty().ifEmpty { "disable" }
    val labels: List<String> = commaSeparated(environment["LABELS"])
    val appSlug: String = environment["APP_SLUG"].orEmpty()
    val base: String = required(environment, "BASE")
    val repository: String = required(environment, "GITHUB_REPOSITORY")
    val apiUrl: String = required(environment, "GITHUB_API_URL").removeSuffix("/")
    val graphqlUrl: String = required(environment, "GITHUB_GRAPHQL_URL")
    val changelogUrl: String = required(environment, "COMPOSE_MULTIPLATFORM_CHANGELOG")
    val mavenRepositories: List<String> =
        required(environment, "MAVEN_REPOSITORIES")
            .lines()
            .map { it.trim().removeSuffix("/") }
            .filter(String::isNotEmpty)
    val apiToken: String = appToken.ifEmpty { token }

    init {
        val problems =
            buildList {
                if (token.isEmpty() == appToken.isEmpty()) {
                    add(
                        "Pass either the token secret, " +
                            "or the app-client-id input together with the app-private-key secret.",
                    )
                }
                if (autoMerge !in autoMergeMethods) {
                    add("auto-merge must be disable, squash, merge or rebase.")
                }
                if (autoMerge != "disable" && !updateTypes.containsAll(autoMergeUpdateTypes.ifEmpty { listOf("") })) {
                    add(
                        "auto-merge-update-types must list the update types to merge automatically, " +
                            "such as minor,patch, unless auto-merge is disable.",
                    )
                }
                if (!File(projectPath(catalogPath)).isFile) {
                    add(
                        "${projectPath(catalogPath)} does not exist. " +
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

    private fun commaSeparated(text: String?): List<String> =
        text
            .orEmpty()
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun required(
        environment: Map<String, String>,
        name: String,
    ): String = environment[name].orEmpty().ifEmpty { error("$name is not set.") }
}

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

fun plan(settings: Settings): List<UpdateUnit> {
    val catalog = VersionCatalog(File(settings.projectPath(catalogPath)))
    val changelog = Changelog(fetch(settings.changelogUrl))
    val groups = changelog.libraryGroups()
    val composeSlots = catalog.slots.filter { slot -> slot.modules.any { composeKind(it, groups) != null } }
    val composeUnit = composeUnit(settings, composeSlots, changelog, groups)
    val otherUnits = otherUnits(catalog.slots - composeSlots.toSet(), settings.mavenRepositories)
    return listOfNotNull(composeUnit) + otherUnits
}

fun composeUnit(
    settings: Settings,
    slots: List<Slot>,
    changelog: Changelog,
    groups: List<LibraryGroup>,
): UpdateUnit? {
    val composeVersions =
        slots
            .filter { slot -> slot.modules.any { composeKind(it, groups) == composeName } }
            .map { it.current }
            .distinct()
    check(composeVersions.isNotEmpty()) {
        "${settings.projectPath(catalogPath)} has no org.jetbrains.compose plugin " +
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
    return UpdateUnit(composeName, current, release, changes.filter { it.slot.current != it.to })
}

fun otherUnits(
    slots: List<Slot>,
    repositories: List<String>,
): List<UpdateUnit> {
    val versions = MavenRepositories(repositories).versions(slots.flatMap { it.modules }.distinct())
    return slots.mapNotNull { slot ->
        val missing = slot.modules.filter { versions.getValue(it).isEmpty() }
        if (missing.isNotEmpty()) {
            val coordinates = listed(missing.map { it.coordinates })
            println("::warning::${slot.label} is not updated, because no repository has $coordinates.")
            null
        } else {
            val available = slot.modules.map { versions.getValue(it) }.reduce { common, next -> common intersect next }
            val target = slot.current.newest(available)
            if (target ==
                slot.current
            ) {
                null
            } else {
                UpdateUnit(slot.name, slot.current, target, listOf(Change(slot, target)))
            }
        }
    }
}

fun composeKind(
    module: Module,
    groups: List<LibraryGroup>,
): String? =
    when {
        module.isPlugin -> if (module.coordinates == composePluginId) composeName else null
        composeModules.any { matches(module.coordinates, it) } -> composeName
        module.coordinates in pinnedLibraries -> null
        else -> groups.firstOrNull { matches(module.coordinates, it.coordinates) }?.name
    }

fun matches(
    coordinates: String,
    pattern: String,
): Boolean = if (pattern.endsWith("*")) coordinates.startsWith(pattern.dropLast(1)) else coordinates == pattern

fun syncBranch(
    settings: Settings,
    github: GitHub,
    baseCommit: String,
    update: BranchUpdate,
) {
    val pullRequest = github.openPullRequest(update.name)
    if (update.units.isEmpty()) {
        closePullRequest(settings, github, update.name, pullRequest)
        return
    }
    git("checkout", "--quiet", "--force", "--detach", baseCommit)
    val catalog = VersionCatalog(File(settings.projectPath(catalogPath)))
    update.units.flatMap { it.changes }.forEach { catalog.set(it.slot, it.to) }
    catalog.save()
    val lockFiles = updateLockFiles(settings)
    val title = title(update.units)
    val body = body(settings, update.units, lockFiles)
    git(listOf("add", "--", settings.projectPath(catalogPath)) + lockFiles.changed)
    git("commit", "--quiet", "--message", title)
    pushBranch(update.name)
    when {
        pullRequest == null -> {
            val created = github.createPullRequest(update.name, title, body)
            if (settings.labels.isNotEmpty()) {
                github.addLabels(created, settings.labels)
            }
            update.autoMerge?.let { github.setAutoMerge(created, it) }
            println("Opened ${created.url}.")
        }

        pullRequest.title != title || pullRequest.body != body -> {
            github.editPullRequest(pullRequest, title, body)
            if (update.autoMerge == null && pullRequest.autoMerge != null) {
                github.setAutoMerge(pullRequest, null)
            }
            println("Updated ${pullRequest.url}.")
        }

        else -> {
            println("${pullRequest.url} already has this update.")
        }
    }
}

fun closePullRequest(
    settings: Settings,
    github: GitHub,
    branch: String,
    pullRequest: PullRequest?,
) {
    if (pullRequest != null) {
        github.comment(pullRequest, "Closing because ${settings.base} needs none of these updates any more.")
        github.closePullRequest(pullRequest)
        println("Closed ${pullRequest.url}.")
    }
    if (git("ls-remote", "--heads", "origin", "refs/heads/$branch").isNotEmpty()) {
        git("push", "--quiet", "origin", "--delete", "refs/heads/$branch")
    }
}

class LockFiles(
    val command: String?,
    val changed: List<String>,
)

fun updateLockFiles(settings: Settings): LockFiles {
    val tracked = git("ls-files", "-z", "--", settings.projectPath("kotlin-js-store")).split("\u0000").toSet()
    val lockFiles = lockFileTasks.keys.filter { settings.projectPath(it) in tracked }
    if (lockFiles.isEmpty()) {
        return LockFiles(null, emptyList())
    }
    val tasks = lockFiles.map(lockFileTasks::getValue)
    val command = (listOf("./gradlew") + tasks).joinToString(" ")
    val process =
        ProcessBuilder(listOf(File(settings.directory, "gradlew").absolutePath) + tasks)
            .directory(File(settings.directory))
            .inheritIO()
            .start()
    check(process.waitFor() == 0) { "$command failed." }
    val diff = git(listOf("diff", "--name-only", "-z", "--") + lockFiles.map(settings::projectPath))
    return LockFiles(command, diff.split("\u0000").filter(String::isNotEmpty))
}

fun title(units: List<UpdateUnit>): String {
    val first = units.first()
    return when (val others = units.size - 1) {
        0 -> "build(deps): update ${first.name} to ${first.to}"
        1 -> "build(deps): update ${first.name} and 1 other dependency"
        else -> "build(deps): update ${first.name} and $others other dependencies"
    }
}

fun body(
    settings: Settings,
    units: List<UpdateUnit>,
    lockFiles: LockFiles,
): String =
    buildString {
        append("Updates the dependencies in `${settings.projectPath(catalogPath)}`. ")
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
        if (units.any { it.name == composeName }) {
            append("- Compose Multiplatform is the latest release in the Compose Multiplatform CHANGELOG ")
            append("that it can move to, and the libraries paired with it are the versions ")
            appendLine("that the CHANGELOG lists for that release.")
        }
        if (units.any { it.name != composeName }) {
            append("- The other dependencies are the latest versions in Maven Central, ")
            appendLine("the Google Maven repository and the Gradle Plugin Portal.")
        }
        append("- A stable version moves only to stable versions. ")
        appendLine("A prerelease moves to a later prerelease of the same version, or to a stable version.")
        lockFiles.command?.let { appendLine(lockFileLine(it, lockFiles.changed)) }
    }

fun lockFileLine(
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

class Version(
    private val text: String,
) : Comparable<Version> {
    private val parts: List<String> = versionParts.findAll(text).map { it.value }.toList()
    private val base: List<BigInteger> = parts.takeWhile(::isNumber).map(::BigInteger)
    private val flavor: List<String> = parts.filterNot { isNumber(it) || isPrerelease(it) }.map(String::lowercase)

    val isStable: Boolean = parts.none(::isPrerelease)

    fun newest(candidates: Collection<Version>): Version = (listOf(this) + candidates.filter(::accepts)).max()

    fun updateType(to: Version): String {
        val mine = levels()
        val theirs = to.levels()
        val changed = mine.zip(theirs).indexOfFirst { (one, other) -> one != other }
        val level =
            when {
                changed >= 0 -> changed
                isStable -> 2
                else -> theirs.indexOfLast { it != BigInteger.ZERO }.coerceAtLeast(0)
            }
        return updateTypes[level]
    }

    override fun compareTo(other: Version): Int {
        val shorter = minOf(parts.size, other.parts.size)
        val extra = parts.getOrNull(shorter) ?: other.parts.getOrNull(shorter)
        val lengthOrder =
            when {
                extra == null -> 0
                (parts.size > other.parts.size) == isNumber(extra) -> 1
                else -> -1
            }
        return parts
            .zip(other.parts)
            .map { (mine, theirs) -> compareParts(mine, theirs) }
            .firstOrNull { it != 0 }
            ?: lengthOrder
    }

    override fun equals(other: Any?): Boolean = other is Version && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    private fun accepts(candidate: Version): Boolean =
        candidate.flavor == flavor && (candidate.isStable || (!isStable && candidate.base == base))

    private fun levels(): List<BigInteger> = (base + List(3) { BigInteger.ZERO }).take(3)
}

fun compareParts(
    mine: String,
    theirs: String,
): Int =
    when {
        isNumber(mine) && isNumber(theirs) -> BigInteger(mine).compareTo(BigInteger(theirs))
        isNumber(mine) -> 1
        isNumber(theirs) -> -1
        else -> compareValuesBy(mine, theirs, ::wordRank, String::lowercase)
    }

fun wordRank(word: String): Int = wordOrder.indexOf(word.lowercase()).let { if (it < 0) 1 else it }

fun isNumber(part: String): Boolean = part.first().isDigit()

fun isPrerelease(part: String): Boolean = !isNumber(part) && part.lowercase() in prereleaseWords

class Module(
    val coordinates: String,
    val isPlugin: Boolean,
) {
    val metadataPath: String =
        if (isPlugin) {
            "${coordinates.replace('.', '/')}/$coordinates.gradle.plugin/maven-metadata.xml"
        } else {
            val (group, name) = coordinates.split(":")
            "${group.replace('.', '/')}/$name/maven-metadata.xml"
        }

    override fun equals(other: Any?): Boolean =
        other is Module && other.coordinates == coordinates && other.isPlugin == isPlugin

    override fun hashCode(): Int = coordinates.hashCode()
}

class Slot(
    val path: List<String>,
    val label: String,
    val name: String,
    val current: Version,
    val modules: List<Module>,
    val render: (Version) -> String,
)

class Dependent(
    val alias: String,
    val value: Any?,
    val module: Module,
    val version: String?,
    val ref: String?,
)

class VersionCatalog(
    private val file: File,
) {
    private val document: TomlParseResult = Toml.parse(file.toPath())

    init {
        check(!document.hasErrors()) { "${file.path} is not valid TOML: ${document.errors().joinToString("; ")}" }
    }

    private val libraries = entries("libraries").map { (alias, value) -> library(alias, value) }
    private val plugins = entries("plugins").map { (alias, value) -> plugin(alias, value) }

    val slots: List<Slot> = keySlots() + inlineSlots("libraries", libraries) + inlineSlots("plugins", plugins)

    fun set(
        slot: Slot,
        version: Version,
    ) {
        document.set(slot.path, slot.render(version))
    }

    fun save() {
        file.writeText(document.toToml())
    }

    private fun keySlots(): List<Slot> {
        val modulesByKey =
            (libraries + plugins)
                .filter { it.ref != null }
                .groupBy({ it.ref.orEmpty() }, { it.module })
        return entries("versions").mapNotNull { (key, value) ->
            val modules = modulesByKey[key].orEmpty().distinct()
            if (value is String && modules.isNotEmpty() && isPlain(value)) {
                val name = modules.singleOrNull()?.coordinates ?: key
                Slot(listOf("versions", key), "versions.$key", name, Version(value), modules) { it.toString() }
            } else {
                null
            }
        }
    }

    private fun inlineSlots(
        table: String,
        dependents: List<Dependent>,
    ): List<Slot> =
        dependents.mapNotNull { dependent ->
            val version = dependent.version?.takeIf(::isPlain) ?: return@mapNotNull null
            val value = dependent.value
            val path =
                if (value is String) {
                    listOf(
                        table,
                        dependent.alias,
                    )
                } else {
                    listOf(table, dependent.alias, "version")
                }
            val module = dependent.module
            Slot(path, "$table.${dependent.alias}", module.coordinates, Version(version), listOf(module)) {
                if (value is String) "${value.substringBeforeLast(":")}:$it" else it.toString()
            }
        }

    private fun isPlain(version: String): Boolean = version.isNotBlank() && !dynamicVersion.containsMatchIn(version)

    private fun entries(table: String): List<Pair<String, Any?>> {
        val entries = document.getTable(table) ?: return emptyList()
        return entries.keySet().map { alias -> alias to entries.get(listOf(alias)) }
    }

    private fun library(
        alias: String,
        value: Any?,
    ): Dependent {
        val parts =
            when (value) {
                is String -> value.split(":")
                is TomlTable -> (value["module"] as? String)?.split(":") ?: listOf(value["group"], value["name"])
                else -> emptyList()
            }
        val group = parts.getOrNull(0) as? String
        val name = parts.getOrNull(1) as? String
        check(
            group != null && name != null && parts.size in 2..3,
        ) { "libraries.$alias in ${file.path} cannot be read." }
        val module = Module("$group:$name", false)
        return Dependent(alias, value, module, version(value, parts.getOrNull(2) as? String), ref(value))
    }

    private fun plugin(
        alias: String,
        value: Any?,
    ): Dependent {
        val parts =
            when (value) {
                is String -> value.split(":")
                is TomlTable -> listOf(value["id"] as? String)
                else -> emptyList()
            }
        val id = parts.getOrNull(0)
        check(id != null && parts.size in 1..2) { "plugins.$alias in ${file.path} cannot be read." }
        return Dependent(alias, value, Module(id, true), version(value, parts.getOrNull(1)), ref(value))
    }

    private fun version(
        value: Any?,
        inline: String?,
    ): String? = if (value is TomlTable) value["version"] as? String else inline

    private fun ref(value: Any?): String? = ((value as? TomlTable)?.get("version") as? TomlTable)?.get("ref") as? String
}

class MavenRepositories(
    private val urls: List<String>,
) {
    fun versions(modules: List<Module>): Map<Module, Set<Version>> {
        val responses =
            modules.associateWith { module ->
                urls.map { url ->
                    client.sendAsync(get("$url/${module.metadataPath}"), HttpResponse.BodyHandlers.ofString())
                }
            }
        return responses.mapValues { (module, futures) ->
            futures
                .map { it.join() }
                .flatMap { response -> versions(module, response) }
                .toSet()
        }
    }

    private fun versions(
        module: Module,
        response: HttpResponse<String>,
    ): List<Version> =
        when (response.statusCode()) {
            200 -> {
                metadataVersion.findAll(response.body()).map { Version(it.groupValues[1]) }.toList()
            }

            403, 404 -> {
                emptyList()
            }

            else -> {
                error(
                    "GET ${response.uri()} for ${module.coordinates} answered with HTTP ${response.statusCode()}.",
                )
            }
        }
}

class LibraryGroup(
    val name: String,
    val coordinates: String,
)

class Components(
    private val release: Version,
    private val lines: List<String>,
) {
    fun version(
        kind: String,
        groups: List<LibraryGroup>,
    ): Version {
        val text =
            if (kind == composeName) {
                lines.firstNotNullOfOrNull { gradlePluginRow.find(it) }?.groupValues?.get(1)
            } else {
                val coordinates = groups.first { it.name == kind }.coordinates
                lines
                    .mapNotNull { libraryRow.matchEntire(it)?.groupValues }
                    .firstOrNull { "${it[2]}:${it[3]}" == coordinates }
                    ?.get(4)
            }
        return text?.let(::Version)
            ?: error("The Compose Multiplatform CHANGELOG section for $release has no $kind version that can be read.")
    }
}

class Changelog(
    text: String,
) {
    private val lines = text.lines()

    fun releases(): List<Version> {
        val releases = lines.mapNotNull(::headingVersion)
        check(releases.any { it.isStable }) { "The Compose Multiplatform CHANGELOG lists no stable release." }
        return releases
    }

    fun components(release: Version): Components {
        val heading = lines.indexOfFirst { headingVersion(it) == release }
        check(heading >= 0) { "The Compose Multiplatform CHANGELOG has no section for $release." }
        val section = lines.drop(heading + 1).takeWhile { !releaseHeading.matches(it) }
        val components =
            section
                .dropWhile { !componentsHeading.matches(it) }
                .drop(1)
                .takeWhile { !secondLevelHeading.matches(it) }
        return Components(release, components)
    }

    fun libraryGroups(): List<LibraryGroup> =
        lines
            .mapNotNull { libraryRow.matchEntire(it)?.groupValues }
            .map { LibraryGroup(it[1], "${it[2]}:${it[3]}") }
            .distinctBy { it.coordinates }

    private fun headingVersion(line: String): Version? =
        releaseHeading
            .matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?.let(::Version)
}

class PullRequest(
    val number: Int,
    val nodeId: String,
    val url: String,
    val title: String,
    val body: String,
    val autoMerge: String?,
)

class GitHub(
    private val settings: Settings,
) {
    private val repository = "/repos/${settings.repository}"

    fun user(appSlug: String): Pair<String, String> {
        val user = if (appSlug.isEmpty()) call("GET", "/user") else call("GET", "/users/${encode("$appSlug[bot]")}")
        val login = user.jsonObject["login"]?.jsonPrimitive?.contentOrNull
        val id = user.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        check(login != null && id != null) { "GitHub did not answer who ${appSlug.ifEmpty { "owns the token" }} is." }
        return login to id
    }

    fun openPullRequest(branch: String): PullRequest? {
        val owner = settings.repository.substringBefore("/")
        val query = "state=open&head=${encode("$owner:$branch")}&base=${encode(settings.base)}"
        return call("GET", "$repository/pulls?$query").jsonArray.firstOrNull()?.let(::pullRequest)
    }

    fun createPullRequest(
        branch: String,
        title: String,
        body: String,
    ): PullRequest {
        val content =
            buildJsonObject {
                put("title", title)
                put("head", branch)
                put("base", settings.base)
                put("body", body)
            }
        return pullRequest(call("POST", "$repository/pulls", content))
    }

    fun editPullRequest(
        pullRequest: PullRequest,
        title: String,
        body: String,
    ) {
        val content =
            buildJsonObject {
                put("title", title)
                put("body", body)
            }
        call("PATCH", "$repository/pulls/${pullRequest.number}", content)
    }

    fun addLabels(
        pullRequest: PullRequest,
        labels: List<String>,
    ) {
        val content = JsonObject(mapOf("labels" to JsonArray(labels.map(::JsonPrimitive))))
        call("POST", "$repository/issues/${pullRequest.number}/labels", content)
    }

    fun comment(
        pullRequest: PullRequest,
        body: String,
    ) {
        call("POST", "$repository/issues/${pullRequest.number}/comments", buildJsonObject { put("body", body) })
    }

    fun closePullRequest(pullRequest: PullRequest) {
        call("PATCH", "$repository/pulls/${pullRequest.number}", buildJsonObject { put("state", "closed") })
    }

    fun setAutoMerge(
        pullRequest: PullRequest,
        method: String?,
    ) {
        val mutation =
            if (method == null) {
                "disablePullRequestAutoMerge(input: {pullRequestId: \$id})"
            } else {
                "enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: ${method.uppercase()}})"
            }
        val content =
            buildJsonObject {
                put("query", "mutation(\$id: ID!) { $mutation { clientMutationId } }")
                put("variables", buildJsonObject { put("id", pullRequest.nodeId) })
            }
        val errors = (send("POST", settings.graphqlUrl, content).jsonObject["errors"] as? JsonArray).orEmpty()
        check(errors.isEmpty()) {
            "GitHub refused to ${if (method == null) "disable" else "enable"} auto-merge on ${pullRequest.url}: " +
                errors.joinToString("; ") {
                    it.jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }
        }
    }

    private fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonElement = send(method, settings.apiUrl + path, body)

    private fun send(
        method: String,
        url: String,
        body: JsonObject?,
    ): JsonElement {
        val publisher =
            body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofMinutes(1))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer ${settings.apiToken}")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "update-compose-multiplatform")
                .method(method, publisher)
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "$method $url answered with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return if (response.body().isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(response.body())
    }
}

fun pullRequest(element: JsonElement): PullRequest {
    val json = element.jsonObject
    return PullRequest(
        json
            .getValue("number")
            .jsonPrimitive.content
            .toInt(),
        json.getValue("node_id").jsonPrimitive.content,
        json.getValue("html_url").jsonPrimitive.content,
        json["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        (json["auto_merge"] as? JsonObject)?.get("merge_method")?.jsonPrimitive?.contentOrNull,
    )
}

fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

fun configureCommitter(
    github: GitHub,
    settings: Settings,
) {
    val (login, id) = github.user(settings.appSlug)
    git("config", "user.name", login)
    git("config", "user.email", "$id+$login@users.noreply.github.com")
}

fun pushBranch(branch: String) {
    val holdsTree =
        git("ls-remote", "--heads", "origin", "refs/heads/$branch").isNotEmpty() &&
            git("fetch", "--quiet", "--depth=1", "origin", "refs/heads/$branch").let {
                git("rev-parse", "FETCH_HEAD^{tree}") == git("rev-parse", "HEAD^{tree}")
            }
    if (holdsTree) {
        println("$branch already holds this update.")
    } else {
        git("push", "--quiet", "--force", "origin", "HEAD:refs/heads/$branch")
    }
}

fun get(url: String): HttpRequest =
    HttpRequest
        .newBuilder(URI(url))
        .timeout(Duration.ofMinutes(1))
        .header("User-Agent", "update-compose-multiplatform")
        .GET()
        .build()

fun fetch(url: String): String {
    val response = client.send(get(url), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "GET $url answered with HTTP ${response.statusCode()}." }
    return response.body()
}

fun git(vararg arguments: String): String = git(arguments.toList())

fun git(arguments: List<String>): String {
    val command = listOf("git") + arguments
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed." }
    return output
}

fun listed(items: List<String>): String =
    if (items.size < 2) items.joinToString() else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

main()
