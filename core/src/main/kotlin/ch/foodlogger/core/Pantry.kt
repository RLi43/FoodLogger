package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The rest of an opened pack the user keeps to eat later. Tracked in grams, so any portion can be
 * taken from it; shown in servings when the product declares a serving size.
 */
@Serializable
data class PantryItem(
    val id: String,
    val product: Product,
    val gramsLeft: Double,
    /** Grams in the pack when it was opened; null when the pack size is unknown. */
    val totalGrams: Double? = null,
    val openedAtMillis: Long,
) {
    val servingsLeft: Double? get() = product.servingGrams?.let { gramsLeft / it }
    val servingsTotal: Double? get() = product.servingGrams?.let { serving -> totalGrams?.let { it / serving } }

    /** Grams that "eat 1" logs: one serving, or what is left when that is less; null without a serving size. */
    val oneServingGrams: Double? get() = product.servingGrams?.coerceAtMost(gramsLeft)
}

/** Opened packs, newest first, persisted by the app as JSON. Packs leave when finished or deleted. */
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

    /** Takes [grams] from the pack [id]; a pack with nothing left is removed. */
    fun eat(list: List<PantryItem>, id: String, grams: Double): List<PantryItem> = list.mapNotNull {
        if (it.id != id) it else it.copy(gramsLeft = it.gramsLeft - grams).takeIf { left -> left.gramsLeft >= FINISHED_BELOW_GRAMS }
    }

    fun remove(list: List<PantryItem>, id: String): List<PantryItem> = list.filter { it.id != id }

    /** The most recently opened pack of the product with [barcode], if one is kept. */
    fun find(list: List<PantryItem>, barcode: String): PantryItem? = list.firstOrNull { it.product.barcode == barcode }

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
