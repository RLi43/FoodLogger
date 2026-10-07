package ch.foodlogger.core

enum class MealSlot {
    BREAKFAST, LUNCH, DINNER, SNACK;

    companion object {
        /** Meal for a local hour of day (0-23) when the user logs food right after eating. */
        fun forHour(hour: Int): MealSlot = when (hour) {
            in 4..10 -> BREAKFAST
            in 11..14 -> LUNCH
            in 17..21 -> DINNER
            else -> SNACK
        }
    }
}
