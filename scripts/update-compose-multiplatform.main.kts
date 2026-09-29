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
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.system.exitProcess

val composeGroup = "org.jetbrains.compose"
val material3Group = "org.jetbrains.compose.material3"
val adaptiveGroup = "org.jetbrains.compose.material3.adaptive"
val composeKey = "composeMultiplatform"
val material3Key = "material3"
val adaptiveKey = "compose-multiplatform-adaptive"
val managedKeys = setOf(composeKey, material3Key, adaptiveKey)
val autoMergeMethods = setOf("disable", "squash", "merge", "rebase")
val mavenCentral = "https://repo1.maven.org/maven2"
val changelog = "https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md"
val changelogDelay: Duration = Duration.ofDays(3)
val adaptiveRow = Regex("""org\.jetbrains\.compose\.material3\.adaptive:adaptive\*:([^`\s|]+)""")
val releaseHeading = Regex("""#+\s+(\d+\.\d+\.\d+\S*)\s+\(.*""")
val client: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

class Version private constructor(
    private val text: String,
    private val order: List<Int>,
) : Comparable<Version> {
    val line: String get() = order.take(2).joinToString(".")

    val isStable: Boolean get() = order[3] == stages.size

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

sealed interface AdaptivePairing

class Paired(
    val version: Version,
) : AdaptivePairing

class Unpaired(
    val reason: String,
    val notice: String?,
) : AdaptivePairing

class Library(
    val alias: String,
    val group: String,
    val name: String,
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

class Update(
    val library: String,
    val key: String,
    val from: String,
    val to: String,
)

class CatalogUpdate(
    val updates: List<Update>,
    val adaptive: AdaptivePairing?,
) {
    val changes: List<Update> = updates.filter { it.from != it.to }

    val title: String = "build(deps): update ${listed(changes.map { "${it.library} to ${it.to}" })}"

    val versions: String = listed(updates.map { "${it.library} ${it.to}" })

    val notice: String = (adaptive as? Unpaired)?.notice.orEmpty()
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

fun request(url: String): HttpRequest.Builder =
    HttpRequest
        .newBuilder(URI(url))
        .timeout(Duration.ofMinutes(1))
        .header("User-Agent", "update-compose-multiplatform")

fun fetch(url: String): String {
    val response = client.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "GET $url answered with HTTP ${response.statusCode()}." }
    return response.body()
}

fun artifactUrl(
    group: String,
    artifact: String,
): String = "$mavenCentral/${group.replace('.', '/')}/$artifact"

fun publishedVersions(
    group: String,
    artifact: String,
): List<String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val metadata =
        factory.newDocumentBuilder().parse(
            InputSource(StringReader(fetch("${artifactUrl(group, artifact)}/maven-metadata.xml"))),
        )
    val versions =
        XPathFactory.newInstance().newXPath().evaluate(
            "/metadata/versioning/versions/version",
            metadata,
            XPathConstants.NODESET,
        ) as NodeList
    return List(versions.length) { versions.item(it).textContent.trim() }
}

fun latestStableCompose(): Version =
    publishedVersions(composeGroup, "compose-gradle-plugin")
        .mapNotNull(Version::parse)
        .filter { it.isStable }
        .maxOrNull()
        ?: error("Maven Central lists no stable release of Compose Multiplatform.")

fun isComposeLibrary(group: String): Boolean =
    group.startsWith("$composeGroup.") && group != material3Group && !group.startsWith("$material3Group.")

fun requiredVersion(dependency: JsonObject): String? {
    val version = dependency["version"] as? JsonObject ?: return null
    return listOf("strictly", "requires", "prefers").firstNotNullOfOrNull { version[it]?.jsonPrimitive?.contentOrNull }
}

fun composeRequirement(material3: Version): Version? {
    val module =
        Json
            .parseToJsonElement(
                fetch("${artifactUrl(material3Group, "material3")}/$material3/material3-$material3.module"),
            ).jsonObject
    val requirements =
        module["variants"]
            ?.jsonArray
            .orEmpty()
            .flatMap { variant -> variant.jsonObject["dependencies"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .filter { isComposeLibrary(it["group"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            .map { requiredVersion(it)?.let(Version::parse) }
    val problem =
        when {
            requirements.isEmpty() -> "it declares no Compose Multiplatform requirement"
            null in requirements -> "one of its Compose Multiplatform requirements cannot be read"
            else -> return requirements.filterNotNull().max()
        }
    println("::warning::material3 $material3 was skipped because $problem.")
    return null
}

fun pairedMaterial3(compose: Version): Version =
    publishedVersions(material3Group, "material3")
        .mapNotNull(Version::parse)
        .filter { it.line == compose.line }
        .sortedDescending()
        .firstOrNull { candidate -> composeRequirement(candidate)?.let { it <= compose } == true }
        ?: error("No material3 release on the ${compose.line} line works with Compose Multiplatform $compose.")

fun releasedAt(compose: Version): Instant? =
    runCatching {
        val pom = "${artifactUrl(composeGroup, "compose-gradle-plugin")}/$compose/compose-gradle-plugin-$compose.pom"
        val response =
            client.send(
                request(pom).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        response
            .headers()
            .firstValue("Last-Modified")
            .map {
                ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.orElse(null)
    }.getOrNull()

fun unpairable(reason: String): Unpaired = Unpaired(reason, reason)

fun waitingFor(
    compose: Version,
    reason: String,
): Unpaired {
    val released = releasedAt(compose)
    val releaseDate = released?.let { LocalDate.ofInstant(it, ZoneOffset.UTC) }
    val notice =
        when {
            released == null -> "$reason The release date of Compose Multiplatform $compose could not be read."
            Duration.between(released, Instant.now()) <= changelogDelay -> null
            else -> "$reason Compose Multiplatform $compose was released on $releaseDate."
        }
    return Unpaired(reason, notice)
}

fun changelogSection(
    text: String,
    compose: Version,
): String? {
    val lines = text.lines()
    val heading = lines.indexOfFirst { releaseHeading.matchEntire(it)?.groupValues?.get(1) == compose.toString() }
    if (heading < 0) {
        return null
    }
    val next = (heading + 1 until lines.size).firstOrNull { releaseHeading.matches(lines[it]) } ?: lines.size
    return lines.subList(heading + 1, next).joinToString("\n")
}

fun changelogPairing(
    compose: Version,
    artifacts: Collection<String>,
): AdaptivePairing {
    val section = changelogSection(fetch(changelog), compose)
    val adaptive =
        section?.let {
            adaptiveRow
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.let(Version::parse)
        }
    val missing =
        adaptive?.let { version -> artifacts.filter { version.toString() !in publishedVersions(adaptiveGroup, it) } }
    return when {
        section == null -> {
            waitingFor(compose, "The Compose Multiplatform CHANGELOG has no section for $compose yet.")
        }

        adaptive == null -> {
            unpairable(
                "The Compose Multiplatform CHANGELOG section for $compose " +
                    "has no Material3 Adaptive version that can be read.",
            )
        }

        missing.isNullOrEmpty() -> {
            Paired(adaptive)
        }

        else -> {
            waitingFor(
                compose,
                "Material3 Adaptive $adaptive, which the CHANGELOG pairs with Compose Multiplatform $compose, " +
                    "is not on Maven Central yet for ${missing.joinToString()}.",
            )
        }
    }
}

fun pairedAdaptive(
    compose: Version,
    artifacts: Collection<String>,
): AdaptivePairing =
    try {
        changelogPairing(compose, artifacts)
    } catch (exception: IOException) {
        unpairable("Material3 Adaptive could not be determined: ${exception.message}")
    } catch (exception: IllegalStateException) {
        unpairable("Material3 Adaptive could not be determined: ${exception.message}")
    } catch (exception: SAXException) {
        unpairable("Material3 Adaptive could not be determined: ${exception.message}")
    }

fun expectedKey(owner: String): String? =
    when {
        owner == adaptiveGroup -> adaptiveKey
        owner == material3Group -> material3Key
        owner == composeGroup || owner.startsWith("$composeGroup.") -> composeKey
        else -> null
    }

fun ownershipProblem(
    entry: String,
    owner: String,
    versionRef: String?,
): String? {
    val expected = expectedKey(owner)
    return when {
        expected != null && versionRef != expected -> {
            "$entry must take its version from versions.$expected."
        }

        expected == null && versionRef in managedKeys -> {
            "$entry must not take its version from versions.$versionRef, which only Compose Multiplatform may use."
        }

        else -> {
            null
        }
    }
}

fun listed(items: List<String>): String =
    if (items.size < 2) items.joinToString() else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

fun pullRequestBody(
    updates: List<Update>,
    adaptive: AdaptivePairing?,
): String =
    buildString {
        append("Updates the Compose Multiplatform libraries that have to move together. ")
        append("The Update Compose Multiplatform workflow updates this pull request on each run ")
        appendLine("and closes it when it is no longer needed.")
        appendLine()
        appendLine("| Library | Version key | From | To |")
        appendLine("| --- | --- | --- | --- |")
        updates.forEach { appendLine("| ${it.library} | `${it.key}` | `${it.from}` | `${it.to}` |") }
        appendLine()
        append("- Compose Multiplatform is the latest stable release on Maven Central, ")
        appendLine("unless the catalog already has a newer version.")
        append("- material3 is the newest release on the same major.minor line as Compose Multiplatform ")
        appendLine("that requires no Compose Multiplatform library newer than it.")
        when (adaptive) {
            is Paired -> {
                append("- Material3 Adaptive is the version that the Compose Multiplatform CHANGELOG pairs ")
                appendLine("with this Compose Multiplatform release.")
            }

            is Unpaired -> {
                appendLine("- Material3 Adaptive is left unchanged. ${adaptive.reason}")
            }

            null -> {
                Unit
            }
        }
    }

fun writeOutputs(
    file: File,
    outputs: Map<String, String>,
) {
    outputs.forEach { (name, value) ->
        println("$name=$value")
        val delimiter = "EOF_${UUID.randomUUID()}"
        file.appendText("$name<<$delimiter\n$value\n$delimiter\n")
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

fun updateVersionCatalog(): CatalogUpdate {
    val catalogFile = File("gradle/libs.versions.toml")
    val catalog = VersionCatalog(catalogFile)
    val libraryProblems =
        catalog.libraries.mapNotNull {
            ownershipProblem("Library ${it.alias} (${it.group}:${it.name})", it.group, it.versionRef)
        }
    val pluginProblems =
        catalog.plugins.mapNotNull { ownershipProblem("Plugin ${it.alias} (${it.id})", it.id, it.versionRef) }
    val problems = libraryProblems + pluginProblems
    check(problems.isEmpty()) { problems.joinToString("\n") }

    val currentCompose = catalog.version(composeKey) ?: error("${catalogFile.path} has no versions.$composeKey.")
    val currentMaterial3 = catalog.version(material3Key) ?: error("${catalogFile.path} has no versions.$material3Key.")
    val adaptiveArtifacts =
        catalog.libraries
            .filter { it.group == adaptiveGroup }
            .map { it.name }
            .distinct()
    val currentAdaptive =
        if (adaptiveArtifacts.isEmpty()) {
            null
        } else {
            catalog.version(adaptiveKey)
                ?: error("${catalogFile.path} has no versions.$adaptiveKey.")
        }

    val compose =
        maxOf(
            latestStableCompose(),
            Version.parse(currentCompose)
                ?: error("versions.$composeKey = \"$currentCompose\" is not a version that this workflow can compare."),
        )
    val material3 = pairedMaterial3(compose)
    val adaptive = currentAdaptive?.let { pairedAdaptive(compose, adaptiveArtifacts) }

    val update =
        CatalogUpdate(
            listOfNotNull(
                Update("Compose Multiplatform", composeKey, currentCompose, compose.toString()),
                Update("material3", material3Key, currentMaterial3, material3.toString()),
                currentAdaptive?.let {
                    Update("Material3 Adaptive", adaptiveKey, it, (adaptive as? Paired)?.version?.toString() ?: it)
                },
            ),
            adaptive,
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
    return "- The yarn.lock files were updated with `${command.joinToString(" ")}`.\n"
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
    branch: String,
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

fun holdsTree(branch: String): Boolean {
    if (git("ls-remote", "--heads", "origin", branch).isEmpty()) {
        return false
    }
    git("fetch", "--depth=1", "origin", branch)
    return git("rev-parse", "FETCH_HEAD^{tree}") == git("rev-parse", "HEAD^{tree}")
}

fun pushBranch(branch: String) {
    if (holdsTree(branch)) {
        println("$branch already holds this update.")
    } else {
        git("push", "--force", "origin", "HEAD:refs/heads/$branch")
    }
}

fun createPullRequest(
    arguments: Arguments,
    title: String,
    body: String,
) {
    val labels = arguments.text("labels")
    val labelOptions = if (labels.isEmpty()) emptyList() else listOf("--label", labels)
    val target = listOf("--head", arguments.text("branch"), "--base", arguments.text("base"))
    val content = listOf("--title", title, "--body-file", body) + labelOptions
    val url = execute(listOf("gh", "pr", "create") + target + content)
    val autoMerge = arguments.text("auto-merge")
    if (autoMerge != "disable") {
        gh("pr", "merge", url, "--auto", "--$autoMerge")
    }
}

fun checkInputs(arguments: Arguments) {
    val usesToken = hasSecret("TOKEN")
    val usesApp = arguments.text("app-client-id").isNotEmpty()
    val updatesYarnLock = arguments.flag("update-js-yarn-lock") || arguments.flag("update-wasm-yarn-lock")
    val jdkIsMissing =
        arguments.text("jdk-distribution-to-update-yarn-lock").isEmpty() ||
            arguments.text("jdk-version-to-update-yarn-lock").isEmpty()
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
                    "Updating a yarn.lock runs Gradle, " +
                        "so pass jdk-distribution-to-update-yarn-lock and jdk-version-to-update-yarn-lock as well.",
                )
            }
            if (arguments.text("auto-merge") !in autoMergeMethods) {
                add("auto-merge must be disable, squash, merge or rebase.")
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
    val update = updateVersionCatalog()
    val changed = update.changes.isNotEmpty()
    if (changed) {
        val yarnLock = if (tasks.isEmpty()) "" else updateYarnLock(tasks)
        directory.mkdirs()
        directory.resolve("title.txt").writeText(update.title)
        directory.resolve("body.md").writeText(pullRequestBody(update.updates, update.adaptive) + yarnLock)
        saveChanges(directory.resolve("changes.patch"))
    }
    writeOutputs(
        arguments.file("github-output"),
        mapOf("changed" to changed.toString(), "versions" to update.versions, "notice" to update.notice),
    )
}

fun openPullRequest(arguments: Arguments) {
    val base = arguments.text("base")
    val branch = arguments.text("branch")
    val directory = arguments.file("update-directory")
    val title = directory.resolve("title.txt").readText()
    val body = directory.resolve("body.md").path
    commitChanges(branch, title, directory.resolve("changes.patch"), arguments.text("app-slug"))
    pushBranch(branch)
    val url = openPullRequestField("url", listOf("--head", branch, "--base", base))
    if (url != null) {
        gh("pr", "edit", url, "--title", title, "--body-file", body)
    } else {
        createPullRequest(arguments, title, body)
    }
}

fun closePullRequest(arguments: Arguments) {
    val repository = arguments.text("repository")
    val base = arguments.text("base")
    val target = listOf("--repo", repository, "--head", arguments.text("branch"), "--base", base)
    val number = openPullRequestField("number", target)
    if (number == null) {
        println("No pull request needs to be closed.")
    } else {
        val comment = "Closing because $base already uses ${arguments.text("versions")}."
        gh("pr", "close", number, "--repo", repository, "--delete-branch", "--comment", comment)
    }
}

fun reportMaterial3Adaptive(arguments: Arguments) {
    error(arguments.text("notice"))
}

val commands: Map<String, Command> =
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
                ),
                ::checkInputs,
            ),
        "prepare" to
            Command(
                listOf("update-js-yarn-lock", "update-wasm-yarn-lock", "update-directory", "github-output"),
                ::prepare,
            ),
        "open-pull-request" to
            Command(
                listOf("base", "branch", "update-directory", "app-slug", "auto-merge", "labels"),
                ::openPullRequest,
            ),
        "close-pull-request" to Command(listOf("repository", "base", "branch", "versions"), ::closePullRequest),
        "report-material3-adaptive" to Command(listOf("notice"), ::reportMaterial3Adaptive),
    )

try {
    val command =
        commands[args.firstOrNull().orEmpty()]
            ?: error("Pass one of ${commands.keys.joinToString()} as the first argument.")
    command.run(args.drop(1))
} catch (failure: IllegalStateException) {
    failure.message
        .orEmpty()
        .lines()
        .forEach { println("::error::$it") }
    exitProcess(1)
}
