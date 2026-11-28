package dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support

class FakeChangelog : AutoCloseable {
    private val text: String = checkNotNull(FakeChangelog::class.java.getResource("/spec/changelog/paired.md")).readText()
    private val server = fakeServer { it.respond(text) }

    val url: String = "${server.url}/CHANGELOG.md"

    override fun close() {
        server.stop(0)
    }
}
