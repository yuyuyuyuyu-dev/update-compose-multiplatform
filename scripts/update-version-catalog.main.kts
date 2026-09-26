@file:DependsOn("org.tomlj:tomlj:2.0.1")
@file:DependsOn("org.checkerframework:checker-qual:4.2.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")

import java.io.File
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.system.exitProcess
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

val composeGroup = "org.jetbrains.compose"
val material3Group = "org.jetbrains.compose.material3"
val adaptiveGroup = "org.jetbrains.compose.material3.adaptive"
val composeKey = "composeMultiplatform"
val material3Key = "material3"
val adaptiveKey = "compose-multiplatform-adaptive"
val managedKeys = setOf(composeKey, material3Key, adaptiveKey)
val mavenCentral = "https://repo1.maven.org/maven2"
val changelog = "https://raw.githubusercontent.com/JetBrains/compose-multiplatform/master/CHANGELOG.md"
val changelogDelay: Duration = Duration.ofDays(3)
val adaptiveRow = Regex("""org\.jetbrains\.compose\.material3\.adaptive:adaptive\*:([^`\s|]+)""")
val releaseHeading = Regex("""#+\s+(\d+\.\d+\.\d+\S*)\s+\(.*""")
val client: HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

class Version private constructor(private val text: String, private val order: List<Int>) : Comparable<Version> {
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
            val (major, minor, patch, stage, number) = pattern.matchEntire(text)?.destructured ?: return null
            val release = listOf(major, minor, patch).map { it.toIntOrNull() ?: return null }
            val prerelease = if (stage.isEmpty()) listOf(stages.size, 0) else listOf(stages.indexOf(stage), number.toIntOrNull() ?: return null)
            return Version(text, release + prerelease)
        }
    }
}

sealed interface AdaptivePairing

class Paired(val version: Version) : AdaptivePairing

class Unpaired(val reason: String, val notice: String?) : AdaptivePairing

class Library(val alias: String, val group: String, val name: String, val versionRef: String?)

class Plugin(val alias: String, val id: String, val versionRef: String?)

class VersionCatalog(private val file: File) {
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

    fun setVersion(key: String, version: String) {
        document.set(listOf("versions", key), version)
    }

    fun save() {
        file.writeText(document.toToml())
    }

    private fun entries(table: String): List<Pair<String, Any?>> {
        val entries = document.getTable(table) ?: return emptyList()
        return entries.keySet().map { alias -> alias to entries.get(listOf(alias)) }
    }

    private fun library(alias: String, value: Any?): Library {
        val coordinates: List<Any?> =
            when (value) {
                is String -> value.split(":").take(2)
                is TomlTable -> (value.get("module") as? String)?.split(":") ?: listOf(value.get("group"), value.get("name"))
                else -> emptyList()
            }
        val group = coordinates.getOrNull(0) as? String
        val name = coordinates.getOrNull(1) as? String
        check(coordinates.size == 2 && group != null && name != null) { "libraries.$alias in ${file.path} cannot be read." }
        return Library(alias, group, name, versionRef(value))
    }

    private fun plugin(alias: String, value: Any?): Plugin {
        val id =
            when (value) {
                is String -> value.substringBefore(":")
                is TomlTable -> value.get("id") as? String
                else -> null
            }
        check(id != null) { "plugins.$alias in ${file.path} cannot be read." }
        return Plugin(alias, id, versionRef(value))
    }

    private fun versionRef(value: Any?): String? = ((value as? TomlTable)?.get("version") as? TomlTable)?.get("ref") as? String
}

class Update(val library: String, val key: String, val from: String, val to: String)

fun request(url: String): HttpRequest.Builder =
    HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofMinutes(1))
        .header("User-Agent", "update-compose-multiplatform")

fun fetch(url: String): String {
    val response = client.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "GET $url answered with HTTP ${response.statusCode()}." }
    return response.body()
}

fun artifactUrl(group: String, artifact: String): String = "$mavenCentral/${group.replace('.', '/')}/$artifact"

fun publishedVersions(group: String, artifact: String): List<String> {
    val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    val metadata = factory.newDocumentBuilder().parse(InputSource(StringReader(fetch("${artifactUrl(group, artifact)}/maven-metadata.xml"))))
    val versions = XPathFactory.newInstance().newXPath().evaluate("/metadata/versioning/versions/version", metadata, XPathConstants.NODESET) as NodeList
    return List(versions.length) { versions.item(it).textContent.trim() }
}

fun latestStableCompose(): Version =
    publishedVersions(composeGroup, "compose-gradle-plugin").mapNotNull(Version::parse).filter { it.isStable }.maxOrNull()
        ?: error("Maven Central lists no stable release of Compose Multiplatform.")

fun isComposeLibrary(group: String): Boolean =
    group.startsWith("$composeGroup.") && group != material3Group && !group.startsWith("$material3Group.")

fun requiredVersion(dependency: JsonObject): String? {
    val version = dependency["version"] as? JsonObject ?: return null
    return listOf("strictly", "requires", "prefers").firstNotNullOfOrNull { version[it]?.jsonPrimitive?.contentOrNull }
}

fun composeRequirement(material3: Version): Version? {
    val module = Json.parseToJsonElement(fetch("${artifactUrl(material3Group, "material3")}/$material3/material3-$material3.module")).jsonObject
    val requirements =
        module["variants"]?.jsonArray.orEmpty()
            .flatMap { variant -> variant.jsonObject["dependencies"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .filter { isComposeLibrary(it["group"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            .map { requiredVersion(it)?.let(Version::parse) }
    if (requirements.isEmpty()) {
        println("::warning::material3 $material3 was skipped because it declares no Compose Multiplatform requirement.")
        return null
    }
    if (null in requirements) {
        println("::warning::material3 $material3 was skipped because one of its Compose Multiplatform requirements cannot be read.")
        return null
    }
    return requirements.filterNotNull().max()
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
        val response = client.send(request(pom).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        response.headers().firstValue("Last-Modified").map { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.orElse(null)
    }.getOrNull()

fun waitingFor(compose: Version, reason: String): Unpaired {
    val released = releasedAt(compose) ?: return Unpaired(reason, "$reason The release date of Compose Multiplatform $compose could not be read.")
    if (Duration.between(released, Instant.now()) <= changelogDelay) {
        return Unpaired(reason, null)
    }
    return Unpaired(reason, "$reason Compose Multiplatform $compose was released on ${released.atOffset(ZoneOffset.UTC).toLocalDate()}.")
}

fun changelogSection(text: String, compose: Version): String? {
    val lines = text.lines()
    val heading = lines.indexOfFirst { releaseHeading.matchEntire(it)?.groupValues?.get(1) == compose.toString() }
    if (heading < 0) {
        return null
    }
    val next = (heading + 1 until lines.size).firstOrNull { releaseHeading.matches(lines[it]) } ?: lines.size
    return lines.subList(heading + 1, next).joinToString("\n")
}

fun pairedAdaptive(compose: Version, artifacts: Collection<String>): AdaptivePairing {
    try {
        val section = changelogSection(fetch(changelog), compose)
            ?: return waitingFor(compose, "The Compose Multiplatform CHANGELOG has no section for $compose yet.")
        val adaptive = adaptiveRow.find(section)?.groupValues?.get(1)?.let(Version::parse)
        if (adaptive == null) {
            val reason = "The Compose Multiplatform CHANGELOG section for $compose has no Material3 Adaptive version that can be read."
            return Unpaired(reason, reason)
        }
        val missing = artifacts.filter { adaptive.toString() !in publishedVersions(adaptiveGroup, it) }
        if (missing.isNotEmpty()) {
            return waitingFor(compose, "Material3 Adaptive $adaptive, which the CHANGELOG pairs with Compose Multiplatform $compose, is not on Maven Central yet for ${missing.joinToString()}.")
        }
        return Paired(adaptive)
    } catch (exception: Exception) {
        val reason = "Material3 Adaptive could not be determined: ${exception.message}"
        return Unpaired(reason, reason)
    }
}

fun expectedKey(owner: String): String? =
    when {
        owner == adaptiveGroup -> adaptiveKey
        owner == material3Group -> material3Key
        owner == composeGroup || owner.startsWith("$composeGroup.") -> composeKey
        else -> null
    }

fun ownershipProblem(entry: String, owner: String, versionRef: String?): String? {
    val expected = expectedKey(owner)
    return when {
        expected != null && versionRef != expected -> "$entry must take its version from versions.$expected."
        expected == null && versionRef in managedKeys -> "$entry must not take its version from versions.$versionRef, which only Compose Multiplatform may use."
        else -> null
    }
}

fun listed(items: List<String>): String =
    if (items.size < 2) items.joinToString() else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

fun pullRequestBody(updates: List<Update>, adaptive: AdaptivePairing?): String =
    buildString {
        appendLine("Updates the Compose Multiplatform libraries that have to move together. The Update Compose Multiplatform workflow updates this pull request on each run and closes it when it is no longer needed.")
        appendLine()
        appendLine("| Library | Version key | From | To |")
        appendLine("| --- | --- | --- | --- |")
        updates.forEach { appendLine("| ${it.library} | `${it.key}` | `${it.from}` | `${it.to}` |") }
        appendLine()
        appendLine("- Compose Multiplatform is the latest stable release on Maven Central, unless the catalog already has a newer version.")
        appendLine("- material3 is the newest release on the same major.minor line as Compose Multiplatform that requires no Compose Multiplatform library newer than it.")
        when (adaptive) {
            is Paired -> appendLine("- Material3 Adaptive is the version that the Compose Multiplatform CHANGELOG pairs with this Compose Multiplatform release.")
            is Unpaired -> appendLine("- Material3 Adaptive is left unchanged. ${adaptive.reason}")
            null -> Unit
        }
    }

fun writeOutputs(outputs: Map<String, String>) {
    val file = System.getenv("GITHUB_OUTPUT")?.let(::File)
    outputs.forEach { (name, value) ->
        println("$name=$value")
        val delimiter = "EOF_${UUID.randomUUID()}"
        file?.appendText("$name<<$delimiter\n$value\n$delimiter\n")
    }
}

fun update(catalogFile: File, bodyFile: File) {
    val catalog = VersionCatalog(catalogFile)
    val problems =
        catalog.libraries.mapNotNull { ownershipProblem("Library ${it.alias} (${it.group}:${it.name})", it.group, it.versionRef) } +
            catalog.plugins.mapNotNull { ownershipProblem("Plugin ${it.alias} (${it.id})", it.id, it.versionRef) }
    check(problems.isEmpty()) { problems.joinToString("\n") }

    val currentCompose = catalog.version(composeKey) ?: error("${catalogFile.path} has no versions.$composeKey.")
    val currentMaterial3 = catalog.version(material3Key) ?: error("${catalogFile.path} has no versions.$material3Key.")
    val adaptiveArtifacts = catalog.libraries.filter { it.group == adaptiveGroup }.map { it.name }.distinct()
    val currentAdaptive =
        if (adaptiveArtifacts.isEmpty()) null else catalog.version(adaptiveKey) ?: error("${catalogFile.path} has no versions.$adaptiveKey.")

    val compose =
        maxOf(
            latestStableCompose(),
            Version.parse(currentCompose) ?: error("versions.$composeKey = \"$currentCompose\" is not a version that this workflow can compare."),
        )
    val material3 = pairedMaterial3(compose)
    val adaptive = currentAdaptive?.let { pairedAdaptive(compose, adaptiveArtifacts) }

    val updates =
        listOfNotNull(
            Update("Compose Multiplatform", composeKey, currentCompose, compose.toString()),
            Update("material3", material3Key, currentMaterial3, material3.toString()),
            currentAdaptive?.let { Update("Material3 Adaptive", adaptiveKey, it, (adaptive as? Paired)?.version?.toString() ?: it) },
        )
    val changes = updates.filter { it.from != it.to }
    if (changes.isNotEmpty()) {
        changes.forEach { catalog.setVersion(it.key, it.to) }
        catalog.save()
        bodyFile.parentFile?.mkdirs()
        bodyFile.writeText(pullRequestBody(updates, adaptive))
    }
    writeOutputs(
        mapOf(
            "changed" to changes.isNotEmpty().toString(),
            "title" to if (changes.isEmpty()) "" else "build(deps): update ${listed(changes.map { "${it.library} to ${it.to}" })}",
            "versions" to listed(updates.map { "${it.library} ${it.to}" }),
            "notice" to ((adaptive as? Unpaired)?.notice ?: ""),
        ),
    )
}

try {
    update(File("gradle/libs.versions.toml"), File(args.single()))
} catch (failure: IllegalStateException) {
    failure.message.orEmpty().lines().forEach { println("::error::$it") }
    exitProcess(1)
}
