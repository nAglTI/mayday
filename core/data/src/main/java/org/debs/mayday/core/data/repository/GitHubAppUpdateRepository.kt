package org.debs.mayday.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.debs.mayday.core.model.AppUpdateInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubAppUpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppUpdateRepository {

    override suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val currentVersion = context.currentVersionName()
            if (currentVersion.isBlank()) {
                return@runCatching null
            }

            val release = fetchLatestRelease()
            if (
                !AppVersionComparator.isNewer(
                    latestVersionTag = release.tagName,
                    currentVersionName = currentVersion,
                )
            ) {
                null
            } else {
                AppUpdateInfo(
                    versionName = release.tagName,
                    releaseUrl = release.url,
                )
            }
        }.getOrNull()
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return connection.use { http ->
            if (http.responseCode !in 200..299) {
                error("GitHub release check failed: HTTP ${http.responseCode}")
            }

            val body = http.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            GitHubRelease(
                tagName = json.optString("tag_name"),
                url = json.optString("html_url").ifBlank { RELEASES_URL },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun Context.currentVersionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private data class GitHubRelease(
        val tagName: String,
        val url: String,
    )

    private companion object {
        const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/nAglTI/mayday/releases/latest"
        const val RELEASES_URL = "https://github.com/nAglTI/mayday/releases"
        const val USER_AGENT = "mayday-android"
        const val NETWORK_TIMEOUT_MS = 10_000
    }
}
