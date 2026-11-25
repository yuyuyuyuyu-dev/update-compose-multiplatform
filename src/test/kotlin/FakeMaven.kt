import java.util.concurrent.ConcurrentHashMap

class FakeMaven {
    private val metadata = ConcurrentHashMap<String, String>()

    fun publish(
        coordinates: String,
        vararg versions: String,
        repository: String = "central",
    ) {
        val (group, name) = coordinates.split(":")
        put(repository, group, name, versions.toList())
    }

    fun publishPlugin(
        id: String,
        vararg versions: String,
        repository: String = "plugins",
    ) {
        put(repository, id, "$id.gradle.plugin", versions.toList())
    }

    fun metadata(path: String): String? = metadata[path]

    private fun put(
        repository: String,
        group: String,
        name: String,
        versions: List<String>,
    ) {
        metadata["$repository/${group.replace('.', '/')}/$name/maven-metadata.xml"] =
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("<metadata>")
                appendLine("  <groupId>$group</groupId>")
                appendLine("  <artifactId>$name</artifactId>")
                appendLine("  <versioning>")
                appendLine("    <versions>")
                versions.forEach { appendLine("      <version>$it</version>") }
                appendLine("    </versions>")
                appendLine("  </versioning>")
                appendLine("</metadata>")
            }
    }
}
