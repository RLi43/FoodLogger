package ch.foodlogger.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import ch.foodlogger.core.MealSlot
import java.time.ZoneId
import java.util.Locale

enum class HealthStatus { Checking, Ready, NeedsPermission, NeedsInstall, Unavailable }

class HealthConnectSink(private val context: Context) : FoodSink {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun status(): HealthStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE ->
            if (client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) {
                HealthStatus.Ready
            } else {
                HealthStatus.NeedsPermission
            }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthStatus.NeedsInstall
        else -> HealthStatus.Unavailable
    }

    override suspend fun log(entry: FoodEntry) {
        val n = entry.product.per100g.forPortion(entry.grams)
        val offset = ZoneId.systemDefault().rules.getOffset(entry.time)
        val record = NutritionRecord(
            // Health Connect needs a non-empty interval; treat the meal as one minute long.
            startTime = entry.time.minusSeconds(60),
            startZoneOffset = offset,
            endTime = entry.time,
            endZoneOffset = offset,
            metadata = Metadata.manualEntry(),
            name = "${entry.product.name} (${formatGrams(entry.grams)} g)",
            mealType = entry.meal.toHealthConnect(),
            energy = n.kcal?.let(Energy::kilocalories),
            totalFat = n.fat?.let(Mass::grams),
            saturatedFat = n.saturatedFat?.let(Mass::grams),
            totalCarbohydrate = n.carbs?.let(Mass::grams),
            sugar = n.sugar?.let(Mass::grams),
            dietaryFiber = n.fiber?.let(Mass::grams),
            protein = n.protein?.let(Mass::grams),
            sodium = n.sodium?.let(Mass::grams),
        )
        client.insertRecords(listOf(record))
    }

    companion object {
        val PERMISSIONS = setOf(HealthPermission.getWritePermission(NutritionRecord::class))
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}

private fun MealSlot.toHealthConnect(): Int = when (this) {
    MealSlot.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
    MealSlot.LUNCH -> MealType.MEAL_TYPE_LUNCH
    MealSlot.DINNER -> MealType.MEAL_TYPE_DINNER
    MealSlot.SNACK -> MealType.MEAL_TYPE_SNACK
}

fun formatGrams(grams: Double): String =
    if (grams % 1.0 == 0.0) grams.toLong().toString() else "%.1f".format(Locale.ROOT, grams)
