package dev.yuyuyuyuyu.updatecomposemultiplatform.nonspec

import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.fakeServer
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.respond
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.url

class EditableChangelog : AutoCloseable {
    var text: String = checkNotNull(EditableChangelog::class.java.getResource("/spec/changelog/paired.md")).readText()
    private val server = fakeServer { it.respond(text) }

    val url: String = "${server.url}/CHANGELOG.md"

    override fun close() {
        server.stop(0)
    }
}
