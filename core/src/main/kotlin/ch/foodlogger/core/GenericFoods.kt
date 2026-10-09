package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One food of the Swiss Food Composition Database (BLV), as converted by `tools/convert_swiss_foods.py`.
 * [names] and [synonyms] are keyed by language code; the edition the app ships may hold only some languages.
 */
@Serializable
data class GenericFood(
    val id: Int,
    val names: Map<String, String>,
    val synonyms: Map<String, String> = emptyMap(),
    /** "Main/Sub", several separated by ";", e.g. "Fruit/Fresh fruit". */
    val category: String? = null,
    /** g/ml, given for liquids. */
    val density: Double? = null,
    val per100g: Nutrients = Nutrients(),
) {
    /** Name in the first of [languages] the database has, else any. */
    fun name(languages: List<String>): String =
        languages.firstNotNullOfOrNull { names[it] } ?: names.values.first()

    fun toProduct(languages: List<String>) = Product(
        barcode = "${GenericFoods.ID_PREFIX}$id",
        name = name(languages),
        per100g = per100g,
        servingGrams = GenericFoods.TYPICAL_PORTIONS[id],
        source = GenericFoods.SOURCE,
    )
}

@Serializable
data class GenericFoodList(val source: String, val foods: List<GenericFood>)

/** The bundled list of generic foods (fruit, bread, cheese, dishes) and searching it. */
object GenericFoods {
    const val SOURCE = "Swiss Food Composition Database"
    const val ID_PREFIX = "sfcd-"

    /** Credit the BLV asks for when the data is reused. */
    const val CREDIT = "Generic foods: Swiss Food Composition Database, Federal Food Safety and Veterinary Office (BLV), naehrwertdaten.ch"

    /**
     * Typical edible weight of one piece or slice in grams, by database ID. The database has no portion sizes,
     * so these are rough estimates for common foods; the user can always type the grams.
     */
    val TYPICAL_PORTIONS: Map<Int, Double> = mapOf(
        378 to 150.0, // Apple, fresh
        381 to 120.0, // Banana, raw (peeled)
        382 to 150.0, // Pear, raw
        405 to 130.0, // Orange, fresh (peeled)
        397 to 60.0, // Mandarin, fresh (peeled)
        395 to 75.0, // Kiwi fruit, raw
        401 to 130.0, // Peach, yellow, fresh
        474 to 30.0, // Plum, fresh
        348 to 100.0, // Tomato, raw
        355 to 80.0, // Carrot, raw
        380 to 140.0, // Avocado, fresh (without stone and skin)
        290 to 55.0, // Egg, raw (medium, without shell)
        836 to 45.0, // Butter croissant, white
        834 to 45.0, // Butter croissant, wholemeal
        10406 to 45.0, // Croissant (average)
        822 to 45.0, // Weggli, bread roll made with butter
        823 to 35.0, // White bread (one slice)
        821 to 40.0, // Wholewheat bread (one slice)
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses the bundled JSON; an unreadable file gives an empty list rather than a crash. */
    fun parse(text: String): GenericFoodList =
        runCatching { json.decodeFromString<GenericFoodList>(text) }.getOrDefault(GenericFoodList("", emptyList()))

    fun isGeneric(product: Product): Boolean = product.barcode.startsWith(ID_PREFIX)

    /**
     * Foods whose names (any language), synonyms or category contain every typed word, best first:
     * a name in [languages] starting with the first word, then any name word starting with a typed word,
     * then other name matches, then matches only by synonym or category; shorter names first within each, then
     * lower IDs (the database numbers basic foods before their products).
     * So "apple" lists "Apple, fresh" before "Pineapple, fresh" and "Apple juice" before "Applesauce".
     */
    fun search(foods: List<GenericFood>, query: String, languages: List<String>, limit: Int = 50): List<GenericFood> {
        val words = FoodSearch.words(query)
        if (words.isEmpty()) return emptyList()
        return foods.mapNotNull { food ->
            val names = food.names.values.joinToString(" ") { FoodSearch.normalize(it) }
            val all = listOf(names, food.synonyms.values.joinToString(" "), food.category.orEmpty())
                .joinToString(" ") { FoodSearch.normalize(it) }
            if (!words.all { it in all }) return@mapNotNull null
            val shown = FoodSearch.normalize(food.name(languages))
            val nameWords = names.split(' ')
            val rank = when {
                shown.startsWith(words.first()) -> 0
                words.all { w -> nameWords.any { it.startsWith(w) } } -> 1
                words.all { it in names } -> 2
                else -> 3
            }
            Triple(food, rank, shown.length)
        }.sortedWith(compareBy({ it.second }, { it.third }, { it.first.id })).take(limit).map { it.first }
    }
}
