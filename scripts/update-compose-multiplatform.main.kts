@file:DependsOn("org.tomlj:tomlj:2.0.1")
@file:DependsOn("org.checkerframework:checker-qual:4.2.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")

import kotlinx.serialization.json.Json
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
val material3Group = "org.jetbrains.compose.material3"
val adaptiveGroup = "org.jetbrains.compose.material3.adaptive"
val composeName = "Compose Multiplatform"
val material3Name = "material3"
val adaptiveName = "Material3 Adaptive"
val branch = "chore/update-compose-multiplatform"
val autoMergeMethods = setOf("disable", "squash", "merge", "rebase")
val updateTypes = listOf("major", "minor", "patch")
val componentRows =
    mapOf(
        composeName to Regex("""`org\.jetbrains\.compose` version `([^`]+)`"""),
        material3Name to Regex("""org\.jetbrains\.compose\.material3:material3\*:([^`\s|]+)"""),
        adaptiveName to Regex("""org\.jetbrains\.compose\.material3\.adaptive:adaptive\*:([^`\s|]+)"""),
    )
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

    fun firstDifference(other: Version): Int = order.zip(other.order).indexOfFirst { (mine, theirs) -> mine != theirs }

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

class Components(
    private val release: Version,
    private val text: String,
) {
    fun version(name: String): Version =
        componentRows
            .getValue(name)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.let(Version::parse)
            ?: error("The Compose Multiplatform CHANGELOG section for $release has no $name version that can be read.")
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
        return Components(release, components.joinToString("\n"))
    }

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
    val type: String?,
) {
    val changes: List<Update> = updates.filter { it.from != it.to }

    val title: String = "build(deps): update ${listed(changes.map { "${it.library} to ${it.to}" })}"

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

fun libraryKind(library: Library): String? =
    when {
        library.group == composeGroup && library.name == "compose-gradle-plugin" -> composeName
        library.group == material3Group && library.name.startsWith("material3") -> material3Name
        library.group == adaptiveGroup && library.name.startsWith("adaptive") -> adaptiveName
        else -> null
    }

fun pluginKind(plugin: Plugin): String? = if (plugin.id == composeGroup) composeName else null

fun dependents(catalog: VersionCatalog): List<Dependent> =
    catalog.libraries.mapNotNull { library ->
        libraryKind(library)?.let {
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

fun sharingProblems(keys: Map<String, String>): List<String> =
    keys.entries
        .groupBy({ it.value }, { it.key })
        .filterValues { it.size > 1 }
        .map { (key, kinds) ->
            "${listed(kinds)} must take their versions from different keys, but share versions.$key."
        }

fun versionKeys(catalog: VersionCatalog): Map<String, String> {
    val dependents = dependents(catalog)
    val refs =
        dependents
            .groupBy({ it.kind }, { it.versionRef })
            .mapValues { (_, it) -> it.distinct() }
    val keys = refs.mapNotNull { (kind, it) -> it.singleOrNull()?.let { key -> kind to key } }.toMap()
    val problems = refProblems(dependents, refs) + sharingProblems(keys)
    check(problems.isEmpty()) { problems.joinToString("\n") }
    return keys
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
        updates.filter { it.library != composeName }.forEach {
            append("- ${it.library} is the version that the Compose Multiplatform CHANGELOG pairs ")
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
    val keys = versionKeys(catalog)
    val composeKey =
        keys[composeName]
            ?: error(
                "${catalogFile.path} has no org.jetbrains.compose plugin or compose-gradle-plugin library. " +
                    "This workflow is only for projects that take Compose Multiplatform from the version catalog.",
            )
    val material3Key =
        keys[material3Name]
            ?: error(
                "${catalogFile.path} has no material3 library. " +
                    "This workflow is only for projects that take material3 from the version catalog.",
            )
    val currentCompose = catalog.version(composeKey) ?: error("${catalogFile.path} has no versions.$composeKey.")
    val currentMaterial3 = catalog.version(material3Key) ?: error("${catalogFile.path} has no versions.$material3Key.")
    val currentAdaptive =
        keys[adaptiveName]?.let { key ->
            key to (catalog.version(key) ?: error("${catalogFile.path} has no versions.$key."))
        }

    val current =
        Version.parse(currentCompose)
            ?: error("versions.$composeKey = \"$currentCompose\" is not a version that this workflow can compare.")
    val release = maxOf(changelog.latestStable(), current)
    val components = changelog.components(release)
    val update =
        CatalogUpdate(
            listOfNotNull(
                Update(composeName, composeKey, currentCompose, components.version(composeName).toString()),
                Update(material3Name, material3Key, currentMaterial3, components.version(material3Name).toString()),
                currentAdaptive?.let { (key, current) ->
                    Update(adaptiveName, key, current, components.version(adaptiveName).toString())
                },
            ),
            updateTypes.getOrNull(current.firstDifference(release)),
        )
    if (update.changes.isNotEmpty()) {
        update.changes.forEach { catalog.setVersion(it.key, it.to) }
        catalog.save()
    }
    return update
}

fun updateYarnLock(tasks: List<String>): String {
    val command = listOf("./gradlew") + tasks
    check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) { "${command.joinToString(" ")} failed." }
    val updated = if (tasks.size == 1) "The yarn.lock file was updated" else "The yarn.lock files were updated"
    return "- $updated with `${command.joinToString(" ")}`.\n"
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

fun autoMergeOptions(
    arguments: Arguments,
    updateType: String?,
): List<String> {
    val method = arguments.text("auto-merge")
    val enabled = method != "disable" && updateType in autoMergeUpdateTypes(arguments)
    return if (enabled) listOf("--auto", "--$method") else listOf("--disable-auto")
}

fun createPullRequest(
    arguments: Arguments,
    directory: File,
) {
    val labels = arguments.text("labels")
    val labelOptions = if (labels.isEmpty()) emptyList() else listOf("--label", labels)
    val title = directory.resolve("title.txt").readText()
    val content = listOf("--title", title, "--body-file", directory.resolve("body.md").path) + labelOptions
    val url = execute(listOf("gh", "pr", "create") + pullRequestTarget(arguments) + content)
    val updateType = directory.resolve("update-type.txt").takeIf(File::exists)?.readText()
    execute(listOf("gh", "pr", "merge", url) + autoMergeOptions(arguments, updateType))
}

fun openPullRequest(
    arguments: Arguments,
    directory: File,
) {
    val title = directory.resolve("title.txt").readText()
    commitChanges(title, directory.resolve("changes.patch"), arguments.text("app-slug"))
    pushBranch()
    val url = openPullRequestField("url", pullRequestTarget(arguments))
    if (url != null) {
        gh("pr", "edit", url, "--title", title, "--body-file", directory.resolve("body.md").path)
    } else {
        createPullRequest(arguments, directory)
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
        update.type?.let { directory.resolve("update-type.txt").writeText(it) }
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
