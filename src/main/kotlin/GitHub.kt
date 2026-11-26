package dev.yuyuyuyuyu.updatecomposemultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PullRequest(
    val number: Int,
    val nodeId: String,
    val url: String,
    val title: String,
    val body: String,
    val autoMerge: String?,
)

class GitHub(
    private val apiUrl: String,
    private val graphqlUrl: String,
    private val repository: String,
    private val token: String,
) {
    private val repositoryPath = "/repos/$repository"

    fun user(appSlug: String): Pair<String, String> {
        val user = call("GET", "/users/${encode("$appSlug[bot]")}")
        val login = user.jsonObject["login"]?.jsonPrimitive?.contentOrNull
        val id = user.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        check(login != null && id != null) { "GitHub did not answer who $appSlug[bot] is." }
        return login to id
    }

    fun openPullRequest(
        branch: String,
        base: String,
    ): PullRequest? {
        val owner = repository.substringBefore("/")
        val query = "state=open&head=${encode("$owner:$branch")}&base=${encode(base)}"
        return call("GET", "$repositoryPath/pulls?$query").jsonArray.firstOrNull()?.let(::pullRequest)
    }

    fun createPullRequest(
        branch: String,
        base: String,
        title: String,
        body: String,
    ): PullRequest {
        val content =
            buildJsonObject {
                put("title", title)
                put("head", branch)
                put("base", base)
                put("body", body)
            }
        return pullRequest(call("POST", "$repositoryPath/pulls", content))
    }

    fun editPullRequest(
        pullRequest: PullRequest,
        title: String,
        body: String,
    ) {
        val content =
            buildJsonObject {
                put("title", title)
                put("body", body)
            }
        call("PATCH", "$repositoryPath/pulls/${pullRequest.number}", content)
    }

    fun comment(
        pullRequest: PullRequest,
        body: String,
    ) {
        call("POST", "$repositoryPath/issues/${pullRequest.number}/comments", buildJsonObject { put("body", body) })
    }

    fun closePullRequest(pullRequest: PullRequest) {
        call("PATCH", "$repositoryPath/pulls/${pullRequest.number}", buildJsonObject { put("state", "closed") })
    }

    fun setAutoMerge(
        pullRequest: PullRequest,
        method: String?,
    ) {
        val mutation =
            if (method == null) {
                "disablePullRequestAutoMerge(input: {pullRequestId: \$id})"
            } else {
                "enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: ${method.uppercase()}})"
            }
        val content =
            buildJsonObject {
                put("query", "mutation(\$id: ID!) { $mutation { clientMutationId } }")
                put("variables", buildJsonObject { put("id", pullRequest.nodeId) })
            }
        val errors = (send("POST", graphqlUrl, content).jsonObject["errors"] as? JsonArray).orEmpty()
        check(errors.isEmpty()) {
            "GitHub refused to ${if (method == null) "disable" else "enable"} auto-merge on ${pullRequest.url}: " +
                errors.joinToString("; ") {
                    it.jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }
        }
    }

    private fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonElement = send(method, apiUrl.removeSuffix("/") + path, body)

    private fun send(
        method: String,
        url: String,
        body: JsonObject?,
    ): JsonElement {
        val publisher =
            body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofMinutes(1))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "update-compose-multiplatform")
                .method(method, publisher)
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "$method $url answered with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return if (response.body().isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(response.body())
    }
}

private fun pullRequest(element: JsonElement): PullRequest {
    val json = element.jsonObject
    return PullRequest(
        json
            .getValue("number")
            .jsonPrimitive.content
            .toInt(),
        json.getValue("node_id").jsonPrimitive.content,
        json.getValue("html_url").jsonPrimitive.content,
        json["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        (json["auto_merge"] as? JsonObject)?.get("merge_method")?.jsonPrimitive?.contentOrNull,
    )
}

private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
