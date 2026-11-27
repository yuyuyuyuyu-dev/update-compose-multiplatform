package dev.yuyuyuyuyu.updatecomposemultiplatform.support

class FakeChangelog : AutoCloseable {
    var text: String = checkNotNull(FakeChangelog::class.java.getResource("/support/changelog/paired.md")).readText()
    private val server = fakeServer { it.respond(text) }

    val url: String = "${server.url}/CHANGELOG.md"

    override fun close() {
        server.stop(0)
    }
}
