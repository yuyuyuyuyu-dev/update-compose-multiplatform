import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val CONNECT_TIMEOUT_SECONDS = 30L

val client: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

fun get(url: String): HttpRequest =
    HttpRequest
        .newBuilder(URI(url))
        .timeout(Duration.ofMinutes(1))
        .header("User-Agent", "update-compose-multiplatform")
        .GET()
        .build()

fun fetch(url: String): String {
    val response = client.send(get(url), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == HttpURLConnection.HTTP_OK) { "GET $url answered with HTTP ${response.statusCode()}." }
    return response.body()
}
