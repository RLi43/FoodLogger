package ch.foodlogger.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds requests for and parses responses from the Open Food Facts API v2. */
object OpenFoodFacts {
    const val SOURCE = "Open Food Facts"

    /**
     * URL of the product endpoint for [barcode], restricted to the fields [parse] reads
     * (via the `fields` query parameter) to keep responses small.
     */
    fun productUrl(barcode: String, languages: List<String>): String {
        val fields = listOf("code", "product_name", "generic_name", "brands", "serving_quantity", "serving_quantity_unit", "nutriments") +
            languages.map { "product_name_$it" }
        return "https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=${fields.joinToString(",")}"
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
