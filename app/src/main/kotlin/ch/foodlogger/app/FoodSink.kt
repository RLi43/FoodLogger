package ch.foodlogger.app

import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Product
import java.time.Instant

/** One eaten portion, ready to be written to a health data store. */
data class FoodEntry(
    val product: Product,
    val grams: Double,
    val meal: MealSlot,
    val time: Instant,
)

/**
 * Destination for logged food. Health Connect is the only one for now; a Google Health
 * cloud API writer can be added behind the same interface if it turns out to be needed.
 */
interface FoodSink {
    suspend fun log(entry: FoodEntry)
}
