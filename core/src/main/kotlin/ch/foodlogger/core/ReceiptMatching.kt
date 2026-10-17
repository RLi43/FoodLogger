package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A product the user chose for a receipt line, remembered under [key] (see [ReceiptMatching.key]). */
@Serializable
data class ReceiptMatch(val key: String, val product: Product)

/**
 * Matching receipt lines to products. Receipts print short names and no barcodes, so a line is matched by
 * its words; the user confirms a match once and it is remembered for that store and name.
 */
object ReceiptMatching {
    /** [Product.source] of the placeholder for a pantry item that still needs a match. */
    const val SOURCE = "Receipt"

    private val json = Json { ignoreUnknownKeys = true }

    /** Words that say nothing about which product it is. */
    private val NOISE = setOf(
        "bio", "pf", "sg", "fg", "asc", "msc", "piece", "pce", "pc", "stk", "stuck", "st", "pz", "pezzo",
        "fairtrade", "ip", "suisse", "schweiz", "svizzera", "ch",
    )

    /** Store shorthand on receipts, normalised, and what it stands for. */
    private val SHORTHAND = mapOf(
        "mclass" to "m-classic",
        "m-cl" to "m-classic",
        "mcl" to "m-classic",
        "mbudget" to "m-budget",
        "m-bud" to "m-budget",
        "saum" to "saumon",
        "pleurot" to "pleurotes",
        "auberg" to "aubergine",
        "gingemb" to "gingembre",
        "prof" to "professional",
        "nat" to "nature",
        "jog" to "joghurt",
    )

    private val PACK_TEXT = Regex("(?i)\\d+\\s*[x×]\\s*\\d+(?:[.,]\\d+)?\\s*(?:kg|g|ml|cl|dl|l)\\b|\\d+(?:[.,]\\d+)?\\s*(?:kg|g|ml|cl|dl|l)\\b")

    /** Remembers matches per store: the article number when the receipt prints one, else the name. */
    fun key(store: Store?, line: ReceiptLine): String {
        val prefix = store?.name ?: "ANY"
        return line.articleNumber?.let { "$prefix:#$it" } ?: "$prefix:${FoodSearch.normalize(line.name)}"
    }

    /** The words of a receipt name worth searching for: shorthand spelled out, pack sizes and noise left out. */
    fun queryWords(name: String): List<String> =
        FoodSearch.words(PACK_TEXT.replace(name, " "))
            .map { SHORTHAND[it] ?: it }
            .filter { word -> word.length > 1 && word !in NOISE && !word.all { it.isDigit() } }

    fun query(name: String): String = queryWords(name).joinToString(" ")

    /**
     * Share of the receipt name's words found in the product's name and brand. Receipt words are often cut
     * short ("filet saum."), so a product word starting with the receipt word counts.
     */
    fun score(line: ReceiptLine, product: Product): Double {
        val words = queryWords(line.name)
        if (words.isEmpty()) return 0.0
        val productWords = FoodSearch.words("${product.name} ${product.brand.orEmpty()}")
        val found = words.count { w -> productWords.any { it.startsWith(w) || (w.length >= 5 && w.startsWith(it) && it.length >= 4) } }
        return found.toDouble() / words.size
    }

    /** True when the receipt names a pack size that the product contradicts (more than 10% off). */
    fun packDisagrees(line: ReceiptLine, product: Product): Boolean {
        val pack = line.pack ?: return false
        val grams = product.packageGrams ?: return false
        return listOf(pack.grams, pack.unitGrams).none { kotlin.math.abs(it - grams) <= 0.1 * it }
    }

    /** A match good enough to take without asking: every word found, and the pack size does not disagree. */
    fun confident(line: ReceiptLine, product: Product): Boolean = score(line, product) >= 1.0 && !packDisagrees(line, product)

    /** The user's own foods that fit [line], best first: at least half its words, pack size not contradicted. */
    fun guesses(line: ReceiptLine, products: List<Product>, limit: Int = 3): List<Product> =
        products.distinctBy { it.barcode }
            .map { it to score(line, it) }
            .filter { (product, score) -> score >= 0.5 && !packDisagrees(line, product) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /** Stand-in product for a pantry item bought but not matched yet; logging asks for the real one first. */
    fun placeholder(store: Store?, line: ReceiptLine): Product = Product(
        barcode = "receipt-${key(store, line)}",
        name = line.name,
        brand = store?.label,
        packageGrams = line.grams?.div(line.packs),
        source = SOURCE,
    )

    fun needsMatch(product: Product): Boolean = product.source == SOURCE

    /** The remembered product for [key], if any. */
    fun find(matches: List<ReceiptMatch>, key: String): Product? = matches.firstOrNull { it.key == key }?.product

    /** Remembers [product] for [key], replacing an earlier choice. */
    fun remember(matches: List<ReceiptMatch>, key: String, product: Product): List<ReceiptMatch> =
        listOf(ReceiptMatch(key, product)) + matches.filter { it.key != key }

    fun encode(matches: List<ReceiptMatch>): String = json.encodeToString(matches)

    /** Decodes [encode] output; returns an empty list for blank or corrupt input. */
    fun decode(text: String): List<ReceiptMatch> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<ReceiptMatch>>(text) }.getOrDefault(emptyList())
}
