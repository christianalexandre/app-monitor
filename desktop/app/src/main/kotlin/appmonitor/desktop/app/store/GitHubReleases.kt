package appmonitor.desktop.app.store

import appmonitor.desktop.data.LatestRelease
import appmonitor.desktop.domain.AvailableUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The repository's newest desktop release from GitHub's REST API (ADR-0228): one unauthenticated `GET` of the release list,
 * nothing about the machine sent but the `User-Agent` GitHub requires. The first published `desktop-X.Y.Z` tag, as `X.Y.Z`
 * (ADR-0232); the SDKs' `X.Y.Z` releases are skipped. Null when there is none, and on any failure.
 */
internal class GitHubReleases(
    private val url: String = RELEASES_URL,
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
                Json
                    .parseToJsonElement(response.body())
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true && it["prerelease"]?.jsonPrimitive?.booleanOrNull != true }
                    .firstNotNullOfOrNull { release ->
                        val tag = release.getValue("tag_name").jsonPrimitive.content
                        if (!tag.startsWith(TAG_PREFIX)) return@firstNotNullOfOrNull null
                        AvailableUpdate(tag.removePrefix(TAG_PREFIX), release.getValue("html_url").jsonPrimitive.content)
                    }
            }.getOrNull()
        }

    companion object {
        // Newest first; SDK releases between two desktop ones stay well under a page.
        const val RELEASES_URL = "https://api.github.com/repos/christianalexandre/app-monitor/releases?per_page=30"
        const val TAG_PREFIX = "desktop-"
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
