package ch.foodlogger.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PantryTest {
    private val cookies = Product("761", "Cookies", servingGrams = 25.0, packageGrams = 200.0, source = "Open Food Facts")
    private val loose = Product("762", "Cheese", source = "Manual")

    private fun item(id: String, product: Product = cookies, left: Double = 150.0, total: Double? = 200.0, opened: Long = 0) =
        PantryItem(id, product, left, total, opened)

    @Test
    fun servings() {
        assertEquals(8.0, Pantry.servingsPerPack(cookies))
        assertNull(Pantry.servingsPerPack(loose))
        val kept = item("a")
        assertEquals(6.0, kept.servingsLeft)
        assertEquals(8.0, kept.servingsTotal)
        assertEquals(25.0, kept.oneServingGrams)
        assertEquals(10.0, item("b", left = 10.0).oneServingGrams)
        assertNull(item("c", product = loose, total = null).servingsLeft)
        assertNull(item("c", product = loose, total = null).oneServingGrams)
    }

    @Test
    fun keepByDefaultNeedsTwoPortions() {
        assertTrue(Pantry.keepByDefault(cookies, 25.0))
        assertTrue(Pantry.keepByDefault(cookies, 100.0))
        assertFalse(Pantry.keepByDefault(cookies, 150.0))
        assertFalse(Pantry.keepByDefault(loose, 25.0))
    }

    @Test
    fun keepAddsNewestFirstAndSkipsEmpty() {
        var list = Pantry.keep(emptyList(), item("a"))
        list = Pantry.keep(list, item("b"))
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(list, Pantry.keep(list, item("c", left = 0.2)))
    }

    @Test
    fun eatCountsDownAndRemovesFinished() {
        var list = listOf(item("a", left = 50.0), item("b"))
        list = Pantry.eat(list, "a", 25.0)
        assertEquals(25.0, list.first { it.id == "a" }.gramsLeft)
        assertEquals(150.0, list.first { it.id == "b" }.gramsLeft)
        list = Pantry.eat(list, "a", 30.0)
        assertEquals(listOf("b"), list.map { it.id })
        assertEquals(list, Pantry.eat(list, "missing", 10.0))
    }

    @Test
    fun removeAndFind() {
        val list = listOf(item("b", opened = 2), item("a", opened = 1))
        assertEquals("b", Pantry.find(list, "761")?.id)
        assertNull(Pantry.find(list, "999"))
        assertEquals(listOf("a"), Pantry.remove(list, "b").map { it.id })
    }

    @Test
    fun daysOpenByCalendarDay() {
        val zone = ZoneId.of("Europe/Zurich")
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        val kept = item("a", opened = at(6, 23))
        assertEquals(0, Pantry.daysOpen(kept, at(6, 23), zone))
        assertEquals(1, Pantry.daysOpen(kept, at(7, 1), zone))
        assertEquals(2, Pantry.daysOpen(kept, at(8, 20), zone))
        assertEquals(0, Pantry.daysOpen(kept, at(5, 12), zone))
    }

    @Test
    fun encodeDecode() {
        val list = listOf(item("a"), item("b", product = loose, total = null))
        assertEquals(list, Pantry.decode(Pantry.encode(list)))
        assertEquals(emptyList(), Pantry.decode(""))
        assertEquals(emptyList(), Pantry.decode("{not json"))
    }

    @Test
    fun productsSavedBeforePackSizeStillLoad() {
        val old = """[{"barcode":"1","name":"Old","per100g":{"kcal":10.0},"servingGrams":30.0,"source":"Manual"}]"""
        assertNull(MyFoods.decode(old).single().packageGrams)
    }

    @Test
    fun oldPantryFilesLoadAsOpenedPacks() {
        val old = """[{"id":"a","product":{"barcode":"761","name":"Cookies","source":"Open Food Facts"},"gramsLeft":150.0,"openedAtMillis":5}]"""
        val item = Pantry.decode(old).single()
        assertTrue(item.opened)
        assertNull(item.boughtAtMillis)
        assertFalse(item.needsMatch)
    }

    @Test
    fun boughtPacksGoAfterOpenedOnesAndOpenWhenEaten() {
        val opened = item("a")
        val older = item("b").copy(opened = false)
        val bought = item("c", left = 200.0).copy(opened = false, boughtAtMillis = 1)
        val list = Pantry.addBought(listOf(opened, older), listOf(bought))
        assertEquals(listOf("a", "c", "b"), list.map { it.id })

        val eaten = Pantry.eat(list, "c", 25.0, nowMillis = 99)
        assertEquals(listOf("c", "a", "b"), eaten.map { it.id })
        assertTrue(eaten.first().opened)
        assertEquals(99, eaten.first().openedAtMillis)
        assertEquals(175.0, eaten.first().gramsLeft)
        // Eating an opened pack keeps its place and opening time.
        assertEquals(listOf("a", "c", "b"), Pantry.eat(list, "a", 25.0, nowMillis = 99).map { it.id })
        assertEquals(0, Pantry.eat(list, "a", 25.0, nowMillis = 99).first().openedAtMillis)
    }

    @Test
    fun findPrefersTheOpenedPack() {
        val unopened = item("u").copy(opened = false)
        val opened = item("o")
        assertEquals("o", Pantry.find(listOf(unopened, opened), "761")?.id)
        assertEquals("u", Pantry.find(listOf(unopened), "761")?.id)
    }

    @Test
    fun matchReplacesThePlaceholder() {
        val placeholder = ReceiptMatching.placeholder(Store.COOP, ReceiptLine("Cookies 200g"))
        val list = listOf(PantryItem("r", placeholder, 200.0, 200.0, 0, opened = false, receiptKey = "COOP:cookies 200g"))
        assertTrue(list.single().needsMatch)
        val matched = Pantry.match(list, "r", cookies).single()
        assertFalse(matched.needsMatch)
        assertEquals(cookies, matched.product)
        assertEquals(200.0, matched.gramsLeft)
    }
}
