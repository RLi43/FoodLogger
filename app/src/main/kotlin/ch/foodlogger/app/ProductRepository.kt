package ch.foodlogger.app

import ch.foodlogger.core.OpenFoodFacts
import ch.foodlogger.core.Product
import ch.foodlogger.core.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface LookupResult {
    data class Found(val product: Product) : LookupResult
    data object NotFound : LookupResult
    data class Failed(val reason: String) : LookupResult
}

/** Looks products up and searches for them on Open Food Facts. */
class ProductRepository(private val userAgent: String) {

    suspend fun lookup(barcode: String, languages: List<String>): LookupResult = withContext(Dispatchers.IO) {
        val connection = URL(OpenFoodFacts.productUrl(barcode, languages)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // Open Food Facts asks every client to identify itself.
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    OpenFoodFacts.parse(barcode, body, languages)
                        ?.let { LookupResult.Found(it) } ?: LookupResult.NotFound
                }
                HttpURLConnection.HTTP_NOT_FOUND -> LookupResult.NotFound
                else -> LookupResult.Failed("Open Food Facts answered HTTP $code")
            }
        } catch (e: IOException) {
            LookupResult.Failed("No connection to Open Food Facts (${e.javaClass.simpleName})")
        } finally {
            connection.disconnect()
        }
    }

    /** Full-text search among products sold in Switzerland; fails with a readable message. */
    suspend fun search(query: String, languages: List<String>): Result<List<SearchHit>> = withContext(Dispatchers.IO) {
        val connection = URL(OpenFoodFacts.searchUrl(query, languages)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            // Full-text search is much slower than a barcode lookup.
            connection.readTimeout = SEARCH_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(OpenFoodFacts.parseSearch(body, languages))
                }
                429, HttpURLConnection.HTTP_UNAVAILABLE ->
                    Result.failure(IOException("Open Food Facts is busy. Try again in a minute."))
                else -> Result.failure(IOException("Open Food Facts answered HTTP $code"))
            }
        } catch (e: IOException) {
            Result.failure(IOException("No connection to Open Food Facts (${e.javaClass.simpleName})"))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val SEARCH_TIMEOUT_MS = 30_000
    }
}
