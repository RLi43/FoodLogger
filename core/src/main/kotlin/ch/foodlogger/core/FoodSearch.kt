package ch.foodlogger.core

import java.text.Normalizer

/** Swiss supermarkets the user can narrow a search to, with the brand names their own products carry. */
enum class Store(val label: String, val keywords: List<String>) {
    MIGROS("Migros", listOf("migros", "m-classic", "m-budget", "anna's best", "selection")),
    COOP("Coop", listOf("coop", "naturaplan", "prix garantie", "qualite & prix", "karma", "fine food", "betty bossi")),
    DENNER("Denner", listOf("denner")),
    ALDI("Aldi", listOf("aldi")),
    LIDL("Lidl", listOf("lidl", "milbona")),
}

/** Matching of typed words against product names and brands, across languages and accents. */
object FoodSearch {
    /** Lower case without accents, with punctuation other than ' & - turned into spaces: "Caffè Latte" → "caffe latte". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}'&-]+"), " ")
            .trim()

    fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    /** True when every word of [query] appears in [texts] (as a word or part of one). */
    fun matches(query: String, vararg texts: String?): Boolean {
        val haystack = texts.filterNotNull().joinToString(" ") { normalize(it) }
        return words(query).all { it in haystack }
    }

    /** True when [store] is null (any store) or one of its names appears in [texts], e.g. a product's brands or stores. */
    fun soldAt(store: Store?, vararg texts: String?): Boolean {
        if (store == null) return true
        val haystack = texts.filterNotNull().joinToString(" ") { normalize(it) }
        return store.keywords.any { normalize(it) in haystack }
    }

    /** Products from the user's own lists matching [query] and [store], without duplicates, in the given order. */
    fun local(products: List<Product>, query: String, store: Store?): List<Product> =
        if (words(query).isEmpty()) emptyList()
        else products.distinctBy { it.barcode }.filter { matches(query, it.name, it.brand) && soldAt(store, it.brand) }

    /**
     * Open Food Facts results sold at [store] (any store when null), at most [limit].
     * Words typed after the search ran ([query] beyond [searchedQuery]) narrow the results already
     * fetched, by name and brand, so refining a search costs no new request.
     */
    fun remote(hits: List<SearchHit>, store: Store?, query: String = "", searchedQuery: String = "", limit: Int = 30): List<SearchHit> {
        val searched = words(searchedQuery)
        val extra = words(query).filter { word -> searched.none { word.startsWith(it) || word in it } }
        return hits.filter { hit ->
            soldAt(store, hit.brands, hit.stores) && extra.all { matches(it, hit.product.name, hit.brands) }
        }.take(limit)
    }
}
