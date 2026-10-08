package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One product in the food history: how often and when it was last logged. */
@Serializable
data class HistoryEntry(val product: Product, val count: Int, val lastUsedMillis: Long)

/**
 * The foods the user logs most, from any source, persisted by the app as JSON.
 * Sorted by [score], so foods logged often and recently come first.
 */
object FoodHistory {
    const val MAX = 100
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Days after which a log counts half as much as one made today. */
    private const val HALF_LIFE_DAYS = 14.0

    private val json = Json { ignoreUnknownKeys = true }

    /** Logs counted with a weight that halves every [HALF_LIFE_DAYS] since the last use. */
    fun score(entry: HistoryEntry, nowMillis: Long): Double {
        val ageDays = (nowMillis - entry.lastUsedMillis).coerceAtLeast(0) / DAY_MS.toDouble()
        return entry.count * Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
    }

    fun sorted(list: List<HistoryEntry>, nowMillis: Long): List<HistoryEntry> =
        list.sortedWith(compareByDescending<HistoryEntry> { score(it, nowMillis) }.thenByDescending { it.lastUsedMillis })

    /** Records one log of [product] (replacing the stored copy, de-duplicated by barcode), capped at [MAX]. */
    fun record(list: List<HistoryEntry>, product: Product, nowMillis: Long): List<HistoryEntry> {
        val count = (list.firstOrNull { it.product.barcode == product.barcode }?.count ?: 0) + 1
        val others = list.filter { it.product.barcode != product.barcode }
        return sorted(others + HistoryEntry(product, count, nowMillis), nowMillis).take(MAX)
    }

    fun remove(list: List<HistoryEntry>, barcode: String): List<HistoryEntry> = list.filter { it.product.barcode != barcode }

    /** Replaces the stored copy of a product, e.g. after the user edited it in My foods. */
    fun update(list: List<HistoryEntry>, product: Product): List<HistoryEntry> =
        list.map { if (it.product.barcode == product.barcode) it.copy(product = product) else it }

    fun encode(list: List<HistoryEntry>): String = json.encodeToString(list)

    /**
     * Decodes [encode] output; returns an empty list for blank or corrupt input.
     * Also reads the older recent list (a plain list of products, newest first), counting each product once.
     */
    fun decode(text: String, nowMillis: Long): List<HistoryEntry> {
        if (text.isBlank()) return emptyList()
        runCatching { json.decodeFromString<List<HistoryEntry>>(text) }.getOrNull()?.let { return sorted(it, nowMillis) }
        val legacy = runCatching { json.decodeFromString<List<Product>>(text) }.getOrNull() ?: return emptyList()
        return legacy.mapIndexed { i, p -> HistoryEntry(p, 1, nowMillis - i) }
    }
}
