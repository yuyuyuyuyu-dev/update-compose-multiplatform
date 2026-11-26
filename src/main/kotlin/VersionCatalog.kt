import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File

const val CATALOG_PATH = "gradle/libs.versions.toml"
private const val MAX_LIBRARY_PARTS = 3
private val dynamicVersion = Regex("""[+\[\](),]|^latest\.""")

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

    override fun equals(other: Any?): Boolean = other is Module && other.coordinates == coordinates && other.isPlugin == isPlugin

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

    fun text(): String = document.toToml()

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
            group != null && name != null && parts.size in 2..MAX_LIBRARY_PARTS,
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
