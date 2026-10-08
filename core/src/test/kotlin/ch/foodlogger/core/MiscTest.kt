package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodesTest {
    @Test
    fun validCodes() {
        assertEquals("7610200337313", Barcodes.normalize("7610200337313"))
        assertEquals("7610200337313", Barcodes.normalize("  7610200337313\n"))
        assertEquals("4006381333931", Barcodes.normalize("4006381333931"))
        assertEquals("96385074", Barcodes.normalize("96385074"))
        assertEquals("036000291452", Barcodes.normalize("036000291452"))
        assertEquals("10012345678902", Barcodes.normalize("10012345678902"))
    }

    @Test
    fun invalidCodes() {
        assertNull(Barcodes.normalize("7610200337310"))
        assertNull(Barcodes.normalize("761020033731"))
        assertNull(Barcodes.normalize("123"))
        assertNull(Barcodes.normalize(""))
        assertNull(Barcodes.normalize("76102003373130"))
        assertNull(Barcodes.normalize("7610200 337313"))
        assertNull(Barcodes.normalize("761020033731A"))
    }
}

class MealSlotTest {
    @Test
    fun hours() {
        val expected = mapOf(
            0 to MealSlot.SNACK, 3 to MealSlot.SNACK, 4 to MealSlot.BREAKFAST, 10 to MealSlot.BREAKFAST,
            11 to MealSlot.LUNCH, 14 to MealSlot.LUNCH, 15 to MealSlot.SNACK, 16 to MealSlot.SNACK,
            17 to MealSlot.DINNER, 21 to MealSlot.DINNER, 22 to MealSlot.SNACK, 23 to MealSlot.SNACK,
        )
        expected.forEach { (h, slot) -> assertEquals(slot, MealSlot.forHour(h), "hour $h") }
    }
}

class NutrientsTest {
    private val n = Nutrients(kcal = 200.0, fat = 10.0, protein = 5.0, salt = 1.0)

    @Test
    fun portion() {
        val p = n.forPortion(250.0)
        assertEquals(Nutrients(kcal = 500.0, fat = 25.0, protein = 12.5, salt = 2.5), p)
        assertNull(p.carbs)
    }

    @Test
    fun scaledZeroKeepsKnown() = assertEquals(0.0, n.scaled(0.0).kcal)

    @Test
    fun sodiumAndEmpty() {
        assertEquals(0.4, n.sodium!!, 1e-9)
        assertNull(Nutrients().sodium)
        assertTrue(Nutrients().isEmpty)
        assertTrue(!Nutrients(fat = 0.0).isEmpty)
    }
}
