package ch.foodlogger.core

import kotlinx.serialization.Serializable

/**
 * Nutrient amounts for some quantity of food (per 100 g for [Product.per100g],
 * or for an eaten portion after [scaled]). Masses are in grams, energy in kcal.
 * A null value means "unknown", which is different from 0.
 */
@Serializable
data class Nutrients(
    val kcal: Double? = null,
    val fat: Double? = null,
    val saturatedFat: Double? = null,
    val carbs: Double? = null,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val protein: Double? = null,
    val salt: Double? = null,
) {
    /** Sodium in grams, derived from salt (salt = sodium x 2.5). */
    val sodium: Double? get() = salt?.div(2.5)

    /** True when no nutrient value is known at all. */
    val isEmpty: Boolean get() = this == Nutrients()

    /** Multiplies every known value by [factor]; unknown values stay null. */
    fun scaled(factor: Double): Nutrients = Nutrients(
        kcal?.times(factor), fat?.times(factor), saturatedFat?.times(factor), carbs?.times(factor),
        sugar?.times(factor), fiber?.times(factor), protein?.times(factor), salt?.times(factor),
    )

    /** Nutrients of a [grams] portion when this instance is per 100 g. */
    fun forPortion(grams: Double): Nutrients = scaled(grams / 100)

    /** Sum of two amounts; a value stays null only when it is unknown on both sides. */
    operator fun plus(other: Nutrients): Nutrients {
        fun add(a: Double?, b: Double?) = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
        return Nutrients(
            add(kcal, other.kcal), add(fat, other.fat), add(saturatedFat, other.saturatedFat), add(carbs, other.carbs),
            add(sugar, other.sugar), add(fiber, other.fiber), add(protein, other.protein), add(salt, other.salt),
        )
    }
}

@Serializable
data class Product(
    val barcode: String,
    /** Product name in the best available language; never blank. */
    val name: String,
    val brand: String? = null,
    val per100g: Nutrients = Nutrients(),
    /** Grams (or ml, treated as grams) in one serving, when the product declares it. */
    val servingGrams: Double? = null,
    /** Grams (or ml) in the whole pack, when known, e.g. 200 for a pack of 8 cookies of 25 g. */
    val packageGrams: Double? = null,
    /** Where the data came from, e.g. "Open Food Facts" or "Manual". */
    val source: String,
)
