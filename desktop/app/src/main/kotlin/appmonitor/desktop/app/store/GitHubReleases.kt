package appmonitor.desktop.app.store

import appmonitor.desktop.data.LatestRelease
import appmonitor.desktop.domain.AvailableUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The repository's latest release from GitHub's REST API (ADR-0228): one unauthenticated `GET`, nothing about the machine
 * sent but the `User-Agent` GitHub requires. Null on any failure.
 */
internal class GitHubReleases(
    private val url: String = LATEST_RELEASE_URL,
    private val version: String = desktopVersion(),
) : LatestRelease {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override suspend fun latest(): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(TIMEOUT)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "AppMonitor/$version")
                        .GET()
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return@runCatching null
                val release = Json.parseToJsonElement(response.body()).jsonObject
                AvailableUpdate(
                    release.getValue("tag_name").jsonPrimitive.content,
                    release.getValue("html_url").jsonPrimitive.content,
                )
            }.getOrNull()
        }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/christianalexandre/app-monitor/releases/latest"
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
