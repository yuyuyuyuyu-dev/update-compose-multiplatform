package dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support

import java.util.concurrent.ConcurrentHashMap

class FakeMaven : AutoCloseable {
    private val metadata = ConcurrentHashMap<String, String>()
    private val server = fakeServer { it.respond(metadata[it.requestURI.path.removePrefix("/")]) }

    val urls: List<String> = listOf("central", "google", "plugins").map { "${server.url}/$it" }

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

    override fun close() {
        server.stop(0)
    }

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
