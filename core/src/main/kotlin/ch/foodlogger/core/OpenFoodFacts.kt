package ch.foodlogger.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import java.net.URLEncoder

/**
 * A product found by [OpenFoodFacts.parseSearch], with its pack size for display and all its brands and
 * stores (the product itself keeps only the first brand) for narrowing results to one store.
 */
data class SearchHit(val product: Product, val quantity: String?, val brands: String?, val stores: String?) {
    val hasNutrition: Boolean get() = !product.per100g.isEmpty
}

/** Builds requests for and parses responses from the Open Food Facts API v2 and its full-text search. */
object OpenFoodFacts {
    const val SOURCE = "Open Food Facts"

    /**
     * URL of the product endpoint for [barcode], restricted to the fields [parse] reads
     * (via the `fields` query parameter) to keep responses small.
     */
    fun productUrl(barcode: String, languages: List<String>): String {
        return "https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=${fields(languages).joinToString(",")}"
    }

    private fun fields(languages: List<String>) =
        listOf("code", "product_name", "generic_name", "brands", "serving_quantity", "serving_quantity_unit", "nutriments") +
            languages.map { "product_name_$it" }

    /** Products fetched per search; enough that filtering by store client-side still leaves a useful list. */
    const val SEARCH_PAGE_SIZE = 100

    /**
     * URL of the full-text search for [query] among products sold in Switzerland, most scanned first.
     * Open Food Facts allows far fewer searches than product lookups, so the app only searches when asked.
     */
    fun searchUrl(query: String, languages: List<String>): String {
        val params = listOf(
            "action" to "process",
            "search_terms" to query.trim(),
            "search_simple" to "1",
            "tagtype_0" to "countries",
            "tag_contains_0" to "contains",
            "tag_0" to "switzerland",
            "sort_by" to "unique_scans_n",
            "page_size" to "$SEARCH_PAGE_SIZE",
            "json" to "1",
            "fields" to (fields(languages) + listOf("quantity", "stores")).joinToString(","),
        )
        return "https://world.openfoodfacts.org/cgi/search.pl?" +
            params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    /**
     * Parses the JSON body of [searchUrl], keeping the server's order (most scanned first) but moving
     * products without nutrition values to the end. Products without a barcode are skipped.
     */
    fun parseSearch(json: String, languages: List<String>): List<SearchHit> {
        val root = runCatching { parser.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
        val products = root["products"] as? JsonArray ?: return emptyList()
        val hits = products.mapNotNull { element ->
            val product = element as? JsonObject ?: return@mapNotNull null
            val code = product.text("code")?.takeIf { code -> code.all { it.isDigit() } } ?: return@mapNotNull null
            SearchHit(productFrom(code, product, languages), product.text("quantity"), product.text("brands"), product.text("stores"))
        }
        return hits.distinctBy { it.product.barcode }.sortedBy { !it.hasNutrition }
    }

    /**
     * Parses the JSON body of the product endpoint.
     *
     * Returns null when the product does not exist (`status` 0 / missing `product`).
     * [languages] are ISO 639-1 codes in order of preference, e.g. ["de", "fr", "en"];
     * the name is taken from `product_name_<lang>` in that order, then `product_name`,
     * then `generic_name`, falling back to "Product <barcode>".
     */
    fun parse(barcode: String, json: String, languages: List<String>): Product? {
        val root = runCatching { parser.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        if (root.number("status") == 0.0) return null
        val product = root["product"] as? JsonObject ?: return null
        return productFrom(barcode, product, languages)
    }

    private fun productFrom(barcode: String, product: JsonObject, languages: List<String>): Product {
        val name = (languages.map { "product_name_$it" } + listOf("product_name", "generic_name"))
            .firstNotNullOfOrNull { product.text(it) } ?: "Product $barcode"
        val unit = product.text("serving_quantity_unit")?.lowercase()
        val nutriments = product["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
        return Product(
            barcode = barcode,
            name = name,
            brand = product.text("brands")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            per100g = Nutrients(
                kcal = nutriments.number("energy-kcal_100g") ?: (nutriments.number("energy-kj_100g")
                    ?: nutriments.number("energy_100g"))?.div(4.184),
                fat = nutriments.number("fat_100g"),
                saturatedFat = nutriments.number("saturated-fat_100g"),
                carbs = nutriments.number("carbohydrates_100g"),
                sugar = nutriments.number("sugars_100g"),
                fiber = nutriments.number("fiber_100g"),
                protein = nutriments.number("proteins_100g"),
                salt = nutriments.number("salt_100g") ?: nutriments.number("sodium_100g")?.times(2.5),
            ),
            servingGrams = product.number("serving_quantity")
                ?.takeIf { it > 0 && (unit == null || unit == "g" || unit == "ml") },
            source = SOURCE,
        )
    }

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** Number given as JSON number or numeric string; negative, NaN and infinite values are ignored. */
    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }
}
