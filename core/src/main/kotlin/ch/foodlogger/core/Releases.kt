package ch.foodlogger.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A published build of the app; CI tags releases "build-<versionCode>". */
data class AppRelease(val versionCode: Int, val apkUrl: String, val notes: String)

/** Reads the GitHub "latest release" API response. */
object Releases {
    const val LATEST_URL = "https://api.github.com/repos/RLi43/FoodLogger/releases/latest"
    private const val TAG_PREFIX = "build-"

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null for malformed JSON, foreign tags, or a release without an APK asset. */
    fun parseLatest(text: String): AppRelease? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val versionCode = root.string("tag_name")?.removePrefix(TAG_PREFIX)?.toIntOrNull() ?: return null
        val apkUrl = (root["assets"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.string("name")?.endsWith(".apk") == true }
            ?.string("browser_download_url") ?: return null
        return AppRelease(versionCode, apkUrl, root.string("body").orEmpty().trim())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
