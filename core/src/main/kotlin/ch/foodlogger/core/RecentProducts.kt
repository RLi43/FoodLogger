package ch.foodlogger.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Most-recently-used product list, persisted by the app as JSON. */
object RecentProducts {
    const val MAX = 30

    private val json = Json { ignoreUnknownKeys = true }

    /** Moves (or inserts) [product] to the front, de-duplicated by barcode, capped at [MAX]. */
    fun push(list: List<Product>, product: Product): List<Product> =
        (listOf(product) + list.filter { it.barcode != product.barcode }).take(MAX)

    fun encode(list: List<Product>): String = json.encodeToString(list)

    /** Decodes [encode] output; returns an empty list for blank or corrupt input. */
    fun decode(json: String): List<Product> =
        if (json.isBlank()) emptyList() else runCatching { this.json.decodeFromString<List<Product>>(json) }.getOrDefault(emptyList())
}
