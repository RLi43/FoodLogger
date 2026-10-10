package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A pack the user keeps to eat later: the rest of an opened pack, or an unopened one added from a receipt.
 * Tracked in grams, so any portion can be taken from it; shown in servings when the product declares a
 * serving size. A pack from a receipt that was not matched to a product yet carries a placeholder product
 * ([ReceiptMatching.needsMatch]) and its [receiptKey], so the match chosen later is remembered.
 */
@Serializable
data class PantryItem(
    val id: String,
    val product: Product,
    val gramsLeft: Double,
    /** Grams in the pack when it was opened; null when the pack size is unknown. */
    val totalGrams: Double? = null,
    /** When the pack was opened; for an unopened pack, when it was added. */
    val openedAtMillis: Long,
    val opened: Boolean = true,
    /** Purchase day from the receipt, at the start of that day. */
    val boughtAtMillis: Long? = null,
    val receiptKey: String? = null,
) {
    val needsMatch: Boolean get() = ReceiptMatching.needsMatch(product)

    val servingsLeft: Double? get() = product.servingGrams?.let { gramsLeft / it }
    val servingsTotal: Double? get() = product.servingGrams?.let { serving -> totalGrams?.let { it / serving } }

    /** Grams that "eat 1" logs: one serving, or what is left when that is less; null without a serving size. */
    val oneServingGrams: Double? get() = product.servingGrams?.coerceAtMost(gramsLeft)
}

/**
 * Kept packs, persisted by the app as JSON: opened packs newest first, then unopened ones.
 * Packs leave when finished or deleted.
 */
object Pantry {
    /** Less than this is crumbs: the pack counts as finished. */
    const val FINISHED_BELOW_GRAMS = 0.5

    private val json = Json { ignoreUnknownKeys = true }

    /** Servings per pack, when both the pack size and the serving size are known. */
    fun servingsPerPack(product: Product): Double? =
        product.packageGrams?.let { pack -> product.servingGrams?.let { pack / it } }

    /** Whether "keep the rest" starts switched on: the pack holds at least two of the portion about to be logged. */
    fun keepByDefault(product: Product, grams: Double): Boolean =
        product.packageGrams?.let { it >= 2 * grams } ?: false

    /** Adds the rest of a pack just opened, unless nothing is left. */
    fun keep(list: List<PantryItem>, item: PantryItem): List<PantryItem> =
        if (item.gramsLeft < FINISHED_BELOW_GRAMS) list else listOf(item) + list.filter { it.id != item.id }

    /** Adds unopened packs bought on a receipt, after the opened packs. */
    fun addBought(list: List<PantryItem>, items: List<PantryItem>): List<PantryItem> {
        val (opened, unopened) = list.partition { it.opened }
        return opened + items + unopened
    }

    /**
     * Takes [grams] from the pack [id]; a pack with nothing left is removed. An unopened pack counts as opened
     * at [nowMillis] and moves up to the opened packs.
     */
    fun eat(list: List<PantryItem>, id: String, grams: Double, nowMillis: Long = System.currentTimeMillis()): List<PantryItem> {
        val item = list.firstOrNull { it.id == id } ?: return list
        val left = item.copy(gramsLeft = item.gramsLeft - grams)
        if (left.gramsLeft < FINISHED_BELOW_GRAMS) return list.filter { it.id != id }
        if (item.opened) return list.map { if (it.id == id) left else it }
        return listOf(left.copy(opened = true, openedAtMillis = nowMillis)) + list.filter { it.id != id }
    }

    /** Gives the pack [id] the product the user matched it to. */
    fun match(list: List<PantryItem>, id: String, product: Product): List<PantryItem> =
        list.map { if (it.id == id) it.copy(product = product) else it }

    fun remove(list: List<PantryItem>, id: String): List<PantryItem> = list.filter { it.id != id }

    /** The most recently opened pack of the product with [barcode], else an unopened one, if one is kept. */
    fun find(list: List<PantryItem>, barcode: String): PantryItem? =
        list.filter { it.product.barcode == barcode }.let { packs -> packs.firstOrNull { it.opened } ?: packs.firstOrNull() }

    /** Calendar days since the pack was opened, in [zone]: 0 today, 1 yesterday. */
    fun daysOpen(item: PantryItem, nowMillis: Long, zone: ZoneId): Long {
        val opened = Instant.ofEpochMilli(item.openedAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(opened, today).coerceAtLeast(0)
    }

    fun encode(list: List<PantryItem>): String = json.encodeToString(list)

    /** Decodes [encode] output; returns an empty list for blank or corrupt input. */
    fun decode(text: String): List<PantryItem> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<PantryItem>>(text) }.getOrDefault(emptyList())
}
