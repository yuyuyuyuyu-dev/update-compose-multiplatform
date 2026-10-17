@file:DependsOn("org.tomlj:tomlj:2.0.1")
@file:DependsOn("org.checkerframework:checker-qual:4.2.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

val composeGroup = "org.jetbrains.compose"
val composeName = "Compose Multiplatform"
val pinnedLibraries =
    setOf(
        "org.jetbrains.compose.material:material-icons-core",
        "org.jetbrains.compose.material:material-icons-extended",
    )
val branch = "chore/update-compose-multiplatform"
val autoMergeMethods = setOf("disable", "squash", "merge", "rebase")
val updateTypes = listOf("major", "minor", "patch")
val gradlePluginRow = Regex("""`org\.jetbrains\.compose` version `([^`]+)`""")
val libraryRow = Regex("""\|\s*([^|`]*?)\s*\|\s*`([^`:\s]+):([^`:\s]+):([^`\s]+)`\s*\|.*""")
val componentsHeading = Regex("""##\s+Components\s*""")
val secondLevelHeading = Regex("""##\s.*""")
val releaseHeading = Regex("""#+\s+(\d+\.\d+\.\d+\S*)\s+\(.*""")
val client: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

fun main(arguments: Array<String>) {
    val commands =
        mapOf(
            "check-inputs" to
                Command(
                    listOf(
                        "update-js-yarn-lock",
                        "update-wasm-yarn-lock",
                        "jdk-distribution-to-update-yarn-lock",
                        "jdk-version-to-update-yarn-lock",
                        "app-client-id",
                        "auto-merge",
                        "auto-merge-update-types",
                    ),
                    ::checkInputs,
                ),
            "prepare" to
                Command(
                    listOf(
                        "update-js-yarn-lock",
                        "update-wasm-yarn-lock",
                        "changelog",
                        "update-directory",
                    ),
                    ::prepare,
                ),
            "sync-pull-request" to
                Command(
                    listOf(
                        "repository",
                        "base",
                        "update-directory",
                        "app-slug",
                        "auto-merge",
                        "auto-merge-update-types",
                        "labels",
                    ),
                    ::syncPullRequest,
                ),
        )
    try {
        val command =
            commands[arguments.firstOrNull().orEmpty()]
                ?: error("Pass one of ${commands.keys.joinToString()} as the first argument.")
        command.run(arguments.drop(1))
    } catch (failure: IllegalStateException) {
        failure.message
            .orEmpty()
            .lines()
            .forEach { println("::error::$it") }
        exitProcess(1)
    }
}

class Version private constructor(
    private val text: String,
    private val order: List<Int>,
) : Comparable<Version> {
    val line: String get() = order.take(2).joinToString(".")

    val isStable: Boolean get() = order[3] == stages.size

    fun updateLevel(to: Version): Int {
        val target = to.order.take(3)
        val changed = order.zip(target).indexOfFirst { (mine, theirs) -> mine != theirs }
        return if (changed >= 0) changed else target.indexOfLast { it != 0 }.coerceAtLeast(0)
    }

    override fun compareTo(other: Version): Int =
        order.zip(other.order).map { (mine, theirs) -> mine.compareTo(theirs) }.firstOrNull { it != 0 } ?: 0

    override fun equals(other: Any?): Boolean = other is Version && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    companion object {
        private val stages = listOf("alpha", "beta", "rc")
        private val pattern = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+))?""")

        fun parse(text: String): Version? {
            val groups = pattern.matchEntire(text)?.groupValues ?: return null
            val stage = groups[4]
            val prerelease =
                if (stage.isEmpty()) listOf(stages.size, 0) else listOf(stages.indexOf(stage), groups[5].toIntOrNull())
            val order = groups.subList(1, 4).map(String::toIntOrNull) + prerelease
            return order.filterNotNull().takeIf { it.size == order.size }?.let { Version(text, it) }
        }
    }
}

class Library(
    val alias: String,
    val group: String,
    val name: String,
    val versionRef: String?,
)

class Dependent(
    val description: String,
    val kind: String,
    val versionRef: String?,
)

class Plugin(
    val alias: String,
    val id: String,
    val versionRef: String?,
)

class VersionCatalog(
    private val file: File,
) {
    private val document: TomlParseResult = Toml.parse(file.toPath())

    init {
        check(!document.hasErrors()) { "${file.path} is not valid TOML: ${document.errors().joinToString("; ")}" }
    }

    val libraries: List<Library> = entries("libraries").map { (alias, value) -> library(alias, value) }

    val plugins: List<Plugin> = entries("plugins").map { (alias, value) -> plugin(alias, value) }

    fun version(key: String): String? {
        val value = document.get(listOf("versions", key))
        check(value == null || value is String) { "versions.$key in ${file.path} must be a plain version." }
        return value as String?
    }

    fun setVersion(
        key: String,
        version: String,
    ) {
        document.set(listOf("versions", key), version)
    }

    fun save() {
        file.writeText(document.toToml())
    }

    private fun entries(table: String): List<Pair<String, Any?>> {
        val entries = document.getTable(table) ?: return emptyList()
        return entries.keySet().map { alias -> alias to entries.get(listOf(alias)) }
    }

    private fun library(
        alias: String,
        value: Any?,
    ): Library {
        val coordinates: List<Any?> =
            when (value) {
                is String -> value.split(":").take(2)
                is TomlTable -> (value["module"] as? String)?.split(":") ?: listOf(value["group"], value["name"])
                else -> emptyList()
            }
        val group = coordinates.getOrNull(0) as? String
        val name = coordinates.getOrNull(1) as? String
        check(coordinates.size == 2 && group != null && name != null) {
            "libraries.$alias in ${file.path} cannot be read."
        }
        return Library(alias, group, name, versionRef(value))
    }

    private fun plugin(
        alias: String,
        value: Any?,
    ): Plugin {
        val id =
            when (value) {
                is String -> value.substringBefore(":")
                is TomlTable -> value["id"] as? String
                else -> null
            }
        check(id != null) { "plugins.$alias in ${file.path} cannot be read." }
        return Plugin(alias, id, versionRef(value))
    }

    private fun versionRef(value: Any?): String? {
        val version = (value as? TomlTable)?.get("version") as? TomlTable
        return version?.get("ref") as? String
    }
}

class LibraryGroup(
    val name: String,
    val coordinates: String,
) {
    private val group = coordinates.substringBefore(":")
    private val artifact = coordinates.substringAfter(":")

    fun contains(library: Library): Boolean =
        library.group == group &&
            "${library.group}:${library.name}" !in pinnedLibraries &&
            if (artifact.endsWith("*")) library.name.startsWith(artifact.dropLast(1)) else library.name == artifact
}

class Components(
    private val release: Version,
    private val lines: List<String>,
) {
    fun composeVersion(): Version =
        lines
            .firstNotNullOfOrNull { gradlePluginRow.find(it) }
            ?.groupValues
            ?.get(1)
            ?.let(Version::parse)
            ?: unreadable(composeName)

    fun version(group: LibraryGroup): Version =
        lines
            .mapNotNull { libraryRow.matchEntire(it)?.groupValues }
            .firstOrNull { "${it[2]}:${it[3]}" == group.coordinates }
            ?.get(4)
            ?.let(Version::parse)
            ?: unreadable(group.name)

    private fun unreadable(name: String): Nothing =
        error("The Compose Multiplatform CHANGELOG section for $release has no $name version that can be read.")
}

class Changelog(
    text: String,
) {
    private val lines = text.lines()

    fun latestStable(): Version =
        lines
            .mapNotNull(::headingVersion)
            .filter { it.isStable }
            .maxOrNull()
            ?: error("The Compose Multiplatform CHANGELOG lists no stable release.")

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
            ?.let(Version::parse)
}

class Update(
    val library: String,
    val key: String,
    val from: String,
    val to: String,
)

class CatalogUpdate(
    val updates: List<Update>,
    val type: String,
) {
    val changes: List<Update> = updates.filter { it.from != it.to }.distinctBy { it.key }

    val title: String =
        updates.first().let {
            if (it.from == it.to) {
                "build(deps): update the libraries paired with $composeName ${it.to}"
            } else {
                "build(deps): update $composeName to ${it.to}"
            }
        }

    val versions: String = listed(updates.map { "${it.library} ${it.to}" })
}

class Arguments(
    private val values: Map<String, String>,
) {
    fun text(name: String): String = values.getValue(name)

    fun flag(name: String): Boolean = text(name).toBooleanStrictOrNull() ?: error("--$name must be true or false.")

    fun file(name: String): File = File(text(name))
}

class Command(
    private val options: List<String>,
    private val action: (Arguments) -> Unit,
) {
    fun run(arguments: List<String>) {
        val pairs = arguments.chunked(2)
        val expected = options.map { "--$it" }
        check(pairs.all { it.size == 2 } && pairs.map { it.first() }.sorted() == expected.sorted()) {
            "Pass ${listed(expected)}, each followed by its value."
        }
        action(Arguments(pairs.associate { (name, value) -> name.removePrefix("--") to value }))
    }
}

fun hasSecret(name: String): Boolean = !System.getenv(name).isNullOrEmpty()

fun executeOrNull(command: List<String>): String? {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    return output.takeIf { process.waitFor() == 0 }
}

fun execute(command: List<String>): String = executeOrNull(command) ?: error("${command.joinToString(" ")} failed.")

fun git(vararg arguments: String): String = execute(listOf("git") + arguments)

fun gh(vararg arguments: String): String = execute(listOf("gh") + arguments)

fun fetch(url: String): String {
    val request =
        HttpRequest
            .newBuilder(URI(url))
            .timeout(Duration.ofMinutes(1))
            .header("User-Agent", "update-compose-multiplatform")
            .GET()
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "GET $url answered with HTTP ${response.statusCode()}." }
    return response.body()
}

fun libraryKind(
    library: Library,
    groups: List<LibraryGroup>,
): String? =
    if (library.group == composeGroup && library.name == "compose-gradle-plugin") {
        composeName
    } else {
        groups.firstOrNull { it.contains(library) }?.name
    }

fun pluginKind(plugin: Plugin): String? = if (plugin.id == composeGroup) composeName else null

fun dependents(
    catalog: VersionCatalog,
    groups: List<LibraryGroup>,
): List<Dependent> =
    catalog.libraries.mapNotNull { library ->
        libraryKind(library, groups)?.let {
            Dependent("Library ${library.alias} (${library.group}:${library.name})", it, library.versionRef)
        }
    } +
        catalog.plugins.mapNotNull { plugin ->
            pluginKind(plugin)?.let { Dependent("Plugin ${plugin.alias} (${plugin.id})", it, plugin.versionRef) }
        }

fun refProblems(
    dependents: List<Dependent>,
    refs: Map<String, List<String?>>,
): List<String> =
    dependents
        .filter { it.versionRef == null }
        .map { "${it.description} must take its version from [versions] with version.ref." } +
        refs
            .mapValues { (_, it) -> it.filterNotNull() }
            .filterValues { it.size > 1 }
            .map { (kind, keys) ->
                "$kind must take its version from one key, but uses ${listed(keys.map { "versions.$it" })}."
            }

fun sharingProblems(updates: List<Update>): List<String> =
    updates
        .groupBy { it.key }
        .filterValues { shared -> shared.map { it.to }.distinct().size > 1 }
        .map { (key, shared) ->
            "${listed(shared.map { it.library })} share versions.$key, " +
                "but the Compose Multiplatform CHANGELOG gives them different versions: " +
                "${listed(shared.map { it.to })}."
        }

fun versionKeys(
    catalog: VersionCatalog,
    groups: List<LibraryGroup>,
): Map<String, String> {
    val dependents = dependents(catalog, groups)
    val refs =
        dependents
            .groupBy({ it.kind }, { it.versionRef })
            .mapValues { (_, it) -> it.distinct() }
    val problems = refProblems(dependents, refs)
    check(problems.isEmpty()) { problems.joinToString("\n") }
    return refs.mapValues { (_, it) -> it.filterNotNull().single() }
}

fun listed(items: List<String>): String =
    if (items.size < 2) items.joinToString() else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

fun pullRequestBody(updates: List<Update>): String =
    buildString {
        append("Updates the Compose Multiplatform libraries that have to move together. ")
        append("The Update Compose Multiplatform workflow updates this pull request on each run ")
        appendLine("and closes it when it is no longer needed.")
        appendLine()
        appendLine("| Library | Version key | From | To |")
        appendLine("| --- | --- | --- | --- |")
        updates.forEach { appendLine("| ${it.library} | `${it.key}` | `${it.from}` | `${it.to}` |") }
        appendLine()
        append("- Compose Multiplatform is the latest stable release in the Compose Multiplatform CHANGELOG, ")
        appendLine("unless the catalog already has a newer version.")
        if (updates.size > 1) {
            append("- The other libraries are the versions that the Compose Multiplatform CHANGELOG pairs ")
            appendLine("with this Compose Multiplatform release.")
        }
    }

fun openPullRequestField(
    field: String,
    options: List<String>,
): String? =
    Json
        .parseToJsonElement(execute(listOf("gh", "pr", "list") + options + listOf("--state", "open", "--json", field)))
        .jsonArray
        .firstOrNull()
        ?.jsonObject
        ?.get(field)
        ?.jsonPrimitive
        ?.content

fun updateVersionCatalog(changelog: Changelog): CatalogUpdate {
    val catalogFile = File("gradle/libs.versions.toml")
    val catalog = VersionCatalog(catalogFile)
    val groups = changelog.libraryGroups()
    val keys = versionKeys(catalog, groups)
    val composeKey =
        keys[composeName]
            ?: error(
                "${catalogFile.path} has no org.jetbrains.compose plugin or compose-gradle-plugin library. " +
                    "This workflow is only for projects that take Compose Multiplatform from the version catalog.",
            )
    val currentVersions =
        keys.mapValues { (_, key) -> catalog.version(key) ?: error("${catalogFile.path} has no versions.$key.") }
    val currentCompose = currentVersions.getValue(composeName)

    val current =
        Version.parse(currentCompose)
            ?: error("versions.$composeKey = \"$currentCompose\" is not a version that this workflow can compare.")
    val release = maxOf(changelog.latestStable(), current)
    val components = changelog.components(release)
    val versions =
        mapOf(composeName to components.composeVersion()) +
            groups.filter { it.name in keys }.associate { it.name to components.version(it) }
    val updates =
        versions.map { (kind, version) ->
            Update(kind, keys.getValue(kind), currentVersions.getValue(kind), version.toString())
        }
    val problems = sharingProblems(updates)
    check(problems.isEmpty()) { problems.joinToString("\n") }
    val update = CatalogUpdate(updates, updateTypes[current.updateLevel(release)])
    if (update.changes.isNotEmpty()) {
        update.changes.forEach { catalog.setVersion(it.key, it.to) }
        catalog.save()
    }
    return update
}

fun updateYarnLock(tasks: List<String>): String {
    val command = listOf("./gradlew") + tasks
    check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) { "${command.joinToString(" ")} failed." }
    val changed = git("diff", "--name-only", "-z").split("\u0000").filter { File(it).name == "yarn.lock" }
    val gradle = "`${command.joinToString(" ")}`"
    return when (changed.size) {
        0 -> "- No yarn.lock file was changed by $gradle.\n"
        1 -> "- The yarn.lock file `${changed.single()}` was updated with $gradle.\n"
        else -> "- The yarn.lock files were updated with $gradle.\n"
    }
}

fun saveChanges(patch: File) {
    val process =
        ProcessBuilder("git", "diff", "--binary")
            .redirectOutput(patch)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    check(process.waitFor() == 0) { "git diff --binary failed." }
}

fun tokenOwner(): String =
    executeOrNull(listOf("gh", "api", "/user"))
        ?.let {
            Json
                .parseToJsonElement(it)
                .jsonObject["login"]
                ?.jsonPrimitive
                ?.contentOrNull
        }
        ?: error(
            "The token secret must belong to a user, such as a personal access token. " +
                "The GITHUB_TOKEN cannot be used, because pull requests opened with it do not run workflows.",
        )

fun commitChanges(
    title: String,
    patch: File,
    appSlug: String,
) {
    val name = appSlug.ifEmpty { null }?.let { "$it[bot]" } ?: tokenOwner()
    val id =
        Json
            .parseToJsonElement(gh("api", "/users/$name"))
            .jsonObject["id"]
            ?.jsonPrimitive
            ?.contentOrNull
    check(id != null) { "GitHub has no user named $name." }
    git("config", "user.name", name)
    git("config", "user.email", "$id+$name@users.noreply.github.com")
    git("switch", "--create", branch)
    git("apply", "--index", patch.path)
    git("commit", "--message", title)
}

fun branchHoldsTree(): Boolean {
    if (git("ls-remote", "--heads", "origin", branch).isEmpty()) {
        return false
    }
    git("fetch", "--depth=1", "origin", branch)
    return git("rev-parse", "FETCH_HEAD^{tree}") == git("rev-parse", "HEAD^{tree}")
}

fun pushBranch() {
    if (branchHoldsTree()) {
        println("$branch already holds this update.")
    } else {
        git("push", "--force", "origin", "HEAD:refs/heads/$branch")
    }
}

fun pullRequestTarget(arguments: Arguments): List<String> =
    listOf("--repo", arguments.text("repository"), "--head", branch, "--base", arguments.text("base"))

fun autoMergeUpdateTypes(arguments: Arguments): List<String> {
    val types = arguments.text("auto-merge-update-types")
    return types.split(",").map(String::trim)
}

fun requestedAutoMerge(
    arguments: Arguments,
    updateType: String,
): String? = arguments.text("auto-merge").takeIf { it != "disable" && updateType in autoMergeUpdateTypes(arguments) }

fun currentAutoMerge(url: String): String? {
    val pullRequest = Json.parseToJsonElement(gh("pr", "view", url, "--json", "autoMergeRequest")).jsonObject
    val request = pullRequest["autoMergeRequest"] as? JsonObject
    return request
        ?.get("mergeMethod")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.lowercase()
}

fun setAutoMerge(
    url: String,
    requested: String?,
) {
    if (requested != currentAutoMerge(url)) {
        val options = requested?.let { listOf("--auto", "--$it") } ?: listOf("--disable-auto")
        execute(listOf("gh", "pr", "merge", url) + options)
    }
}

fun createPullRequest(
    arguments: Arguments,
    directory: File,
): String {
    val labels = arguments.text("labels")
    val labelOptions = if (labels.isEmpty()) emptyList() else listOf("--label", labels)
    val title = directory.resolve("title.txt").readText()
    val content = listOf("--title", title, "--body-file", directory.resolve("body.md").path) + labelOptions
    return execute(listOf("gh", "pr", "create") + pullRequestTarget(arguments) + content)
}

fun openPullRequest(
    arguments: Arguments,
    directory: File,
) {
    val title = directory.resolve("title.txt").readText()
    val autoMerge = requestedAutoMerge(arguments, directory.resolve("update-type.txt").readText())
    commitChanges(title, directory.resolve("changes.patch"), arguments.text("app-slug"))
    pushBranch()
    val url = openPullRequestField("url", pullRequestTarget(arguments))
    if (url == null) {
        setAutoMerge(createPullRequest(arguments, directory), autoMerge)
    } else {
        val updateChanged = openPullRequestField("title", pullRequestTarget(arguments)) != title
        gh("pr", "edit", url, "--title", title, "--body-file", directory.resolve("body.md").path)
        if (updateChanged && autoMerge == null) {
            setAutoMerge(url, null)
        }
    }
}

fun closePullRequest(
    arguments: Arguments,
    versions: String,
) {
    val number = openPullRequestField("number", pullRequestTarget(arguments))
    if (number == null) {
        println("No pull request needs to be closed.")
    } else {
        val comment = "Closing because ${arguments.text("base")} already uses $versions."
        gh("pr", "close", number, "--repo", arguments.text("repository"), "--delete-branch", "--comment", comment)
    }
}

fun checkInputs(arguments: Arguments) {
    val usesToken = hasSecret("TOKEN")
    val usesApp = arguments.text("app-client-id").isNotEmpty()
    val updatesYarnLock = arguments.flag("update-js-yarn-lock") || arguments.flag("update-wasm-yarn-lock")
    val jdkIsMissing =
        arguments.text("jdk-distribution-to-update-yarn-lock").isEmpty() ||
            arguments.text("jdk-version-to-update-yarn-lock").isEmpty()
    val autoMerge = arguments.text("auto-merge")
    val problems =
        buildList {
            if (usesToken == usesApp || usesApp != hasSecret("APP_PRIVATE_KEY")) {
                add(
                    "Pass either the token secret, " +
                        "or the app-client-id input together with the app-private-key secret.",
                )
            }
            if (updatesYarnLock && jdkIsMissing) {
                add(
                    "Updating yarn.lock runs Gradle, " +
                        "so pass jdk-distribution-to-update-yarn-lock and jdk-version-to-update-yarn-lock as well.",
                )
            }
            if (autoMerge !in autoMergeMethods) {
                add("auto-merge must be disable, squash, merge or rebase.")
            }
            if (autoMerge != "disable" && !updateTypes.containsAll(autoMergeUpdateTypes(arguments))) {
                add(
                    "auto-merge-update-types must list the update types to merge automatically, " +
                        "such as minor,patch, unless auto-merge is disable.",
                )
            }
        }
    check(problems.isEmpty()) { problems.joinToString("\n") }
}

fun prepare(arguments: Arguments) {
    val directory = arguments.file("update-directory")
    val tasks =
        listOfNotNull(
            "kotlinUpgradeYarnLock".takeIf { arguments.flag("update-js-yarn-lock") },
            "kotlinWasmUpgradeYarnLock".takeIf { arguments.flag("update-wasm-yarn-lock") },
        )
    val update = updateVersionCatalog(Changelog(fetch(arguments.text("changelog"))))
    directory.mkdirs()
    directory.resolve("versions.txt").writeText(update.versions)
    if (update.changes.isEmpty()) {
        println("The catalog already uses ${update.versions}.")
    } else {
        val yarnLock = if (tasks.isEmpty()) "" else updateYarnLock(tasks)
        directory.resolve("title.txt").writeText(update.title)
        directory.resolve("update-type.txt").writeText(update.type)
        directory.resolve("body.md").writeText(pullRequestBody(update.updates) + yarnLock)
        saveChanges(directory.resolve("changes.patch"))
        println("The catalog now uses ${update.versions}.")
    }
}

fun syncPullRequest(arguments: Arguments) {
    val directory = arguments.file("update-directory")
    if (directory.resolve("changes.patch").exists()) {
        openPullRequest(arguments, directory)
    } else {
        closePullRequest(arguments, directory.resolve("versions.txt").readText())
    }
}

main(args)
