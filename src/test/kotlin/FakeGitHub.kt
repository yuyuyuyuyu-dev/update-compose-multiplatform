import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

const val REPOSITORY = "octo-org/octo-app"

data class FakePullRequest(
    val number: Int,
    val head: String,
    val base: String,
    var title: String,
    var body: String,
    var state: String = "open",
    var autoMerge: String? = null,
    val comments: MutableList<String> = mutableListOf(),
)

class FakeGitHub {
    private val pullRequestPath = Regex("/repos/$REPOSITORY/pulls/(\\d+)")
    private val commentPath = Regex("/repos/$REPOSITORY/issues/(\\d+)/comments")

    val calls = AtomicInteger()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val pullRequests: MutableList<FakePullRequest> = Collections.synchronizedList(mutableListOf())

    fun openPullRequest(
        head: String,
        title: String,
        body: String,
    ): FakePullRequest = FakePullRequest(pullRequests.size + 1, head, "main", title, body).also(pullRequests::add)

    fun pullRequest(head: String): FakePullRequest = pullRequests.single { it.head == head }

    fun handle(exchange: HttpExchange) {
        calls.incrementAndGet()
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath.removePrefix("/api")
        val body = exchange.requestBody.bufferedReader().readText()
        val json = if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
        if (method != "GET") {
            requests += if (path == "/graphql") "$method $path ${mutationName(json)}" else "$method $path"
        }
        val authorized =
            exchange.requestHeaders
                .getFirst("Authorization")
                .orEmpty()
                .removePrefix("Bearer ")
                .isNotBlank()
        val response = if (authorized) route(method, path, exchange.requestURI.rawQuery.orEmpty(), json) else null
        val bytes = response?.toString()?.toByteArray()
        exchange.sendResponseHeaders(if (bytes == null) 404 else 200, bytes?.size?.toLong() ?: -1)
        bytes?.let { exchange.responseBody.write(it) }
        exchange.close()
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        json: JsonObject,
    ): JsonElement? =
        when {
            method == "GET" && path.startsWith("/users/") -> user(path)
            method == "GET" && path == "/repos/$REPOSITORY/pulls" -> listPullRequests(query)
            method == "POST" && path == "/repos/$REPOSITORY/pulls" -> createPullRequest(json)
            method == "PATCH" && pullRequestPath.matches(path) -> editPullRequest(path, json)
            method == "POST" && commentPath.matches(path) -> comment(path, json)
            method == "POST" && path == "/graphql" -> setAutoMerge(json)
            else -> null
        }

    private fun user(path: String): JsonObject =
        buildJsonObject {
            put("login", decode(path.removePrefix("/users/")))
            put("id", 41898282)
        }

    private fun listPullRequests(query: String): JsonArray {
        val parameters =
            query
                .split(
                    "&",
                ).associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=")) }
        val matching =
            pullRequests.filter {
                it.state == parameters["state"] && "octo-org:${it.head}" == parameters["head"] &&
                    it.base == parameters["base"]
            }
        return JsonArray(matching.map(::json))
    }

    private fun createPullRequest(json: JsonObject): JsonObject {
        val created =
            FakePullRequest(
                pullRequests.size + 1,
                json.text("head"),
                json.text("base"),
                json.text("title"),
                json.text("body"),
            )
        pullRequests += created
        return json(created)
    }

    private fun editPullRequest(
        path: String,
        json: JsonObject,
    ): JsonObject {
        val pullRequest = numbered(pullRequestPath.matchEntire(path)!!.groupValues[1])
        json["title"]?.let { pullRequest.title = it.jsonPrimitive.content }
        json["body"]?.let { pullRequest.body = it.jsonPrimitive.content }
        json["state"]?.let { pullRequest.state = it.jsonPrimitive.content }
        return json(pullRequest)
    }

    private fun comment(
        path: String,
        json: JsonObject,
    ): JsonObject {
        numbered(commentPath.matchEntire(path)!!.groupValues[1]).comments += json.text("body")
        return JsonObject(emptyMap())
    }

    private fun setAutoMerge(json: JsonObject): JsonObject {
        val pullRequest =
            numbered(
                json
                    .getValue("variables")
                    .jsonObject
                    .text("id")
                    .removePrefix("PR_"),
            )
        pullRequest.autoMerge =
            Regex("mergeMethod: (\\w+)")
                .find(json.text("query"))
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return buildJsonObject { put("data", JsonObject(emptyMap())) }
    }

    private fun numbered(number: String): FakePullRequest = pullRequests.single { it.number == number.toInt() }

    private fun json(pullRequest: FakePullRequest): JsonObject =
        buildJsonObject {
            put("number", pullRequest.number)
            put("node_id", "PR_${pullRequest.number}")
            put("html_url", "https://github.com/$REPOSITORY/pull/${pullRequest.number}")
            put("title", pullRequest.title)
            put("body", pullRequest.body)
            put("auto_merge", pullRequest.autoMerge?.let { buildJsonObject { put("merge_method", it) } } ?: JsonNull)
        }

    private fun mutationName(json: JsonObject): String =
        Regex("\\{\\s*(\\w+)\\(")
            .find(json.text("query"))
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private fun decode(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
