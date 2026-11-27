package dev.yuyuyuyuyu.updatecomposemultiplatform.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun fakeServer(handle: (HttpExchange) -> Unit): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { handle(it) }
        start()
    }

val HttpServer.url: String get() = "http://127.0.0.1:${address.port}"

fun HttpExchange.respond(body: String?) {
    val bytes = body?.toByteArray()
    sendResponseHeaders(if (bytes == null) 404 else 200, bytes?.size?.toLong() ?: -1)
    bytes?.let { responseBody.write(it) }
    close()
}
