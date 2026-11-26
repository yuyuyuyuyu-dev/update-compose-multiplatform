import java.net.HttpURLConnection
import java.net.http.HttpResponse

private val metadataVersion = Regex("""<version>\s*([^<\s]+)\s*</version>""")

class MavenRepositories(
    private val urls: List<String>,
) {
    fun versions(modules: List<Module>): Map<Module, Set<Version>> {
        val responses =
            modules.associateWith { module ->
                urls.map { url ->
                    client.sendAsync(get("$url/${module.metadataPath}"), HttpResponse.BodyHandlers.ofString())
                }
            }
        return responses.mapValues { (module, futures) ->
            futures
                .map { it.join() }
                .flatMap { response -> versions(module, response) }
                .toSet()
        }
    }

    private fun versions(
        module: Module,
        response: HttpResponse<String>,
    ): List<Version> =
        when (response.statusCode()) {
            HttpURLConnection.HTTP_OK -> {
                metadataVersion.findAll(response.body()).map { Version(it.groupValues[1]) }.toList()
            }

            HttpURLConnection.HTTP_FORBIDDEN, HttpURLConnection.HTTP_NOT_FOUND -> {
                emptyList()
            }

            else -> {
                error(
                    "GET ${response.uri()} for ${module.coordinates} answered with HTTP ${response.statusCode()}.",
                )
            }
        }
}
