package ch.foodlogger.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's own food database: products entered by hand or read from a label.
 * Unlike the public databases it can be edited, and entries stay until the user deletes them.
 */
object MyFoods {
    private val json = Json { ignoreUnknownKeys = true }

    /** Adds [product], or replaces the entry with the same barcode, and keeps the list sorted by name. */
    fun save(list: List<Product>, product: Product): List<Product> =
        (list.filter { it.barcode != product.barcode } + product).sortedBy { FoodSearch.normalize(it.name) }

    fun remove(list: List<Product>, barcode: String): List<Product> = list.filter { it.barcode != barcode }

    fun encode(list: List<Product>): String = json.encodeToString(list)

    /** Decodes [encode] output; returns an empty list for blank or corrupt input. */
    fun decode(text: String): List<Product> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Product>>(text) }.getOrDefault(emptyList())
}
