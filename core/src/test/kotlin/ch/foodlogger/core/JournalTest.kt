package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun entry(id: String, at: Long, kcal: Double? = 100.0, protein: Double? = null) =
        LoggedEntry(id, "Food $id", 100.0, MealSlot.LUNCH, Nutrients(kcal = kcal, protein = protein), at)

    @Test
    fun addKeepsNewestFirstAndReplacesSameId() {
        val list = Journal.add(Journal.add(listOf(entry("a", 1_000)), entry("b", 3_000)), entry("c", 2_000))
        assertEquals(listOf("b", "c", "a"), list.map { it.recordId })
        val replaced = Journal.add(list, entry("a", 4_000))
        assertEquals(listOf("a", "b", "c"), replaced.map { it.recordId })
    }

    @Test
    fun addPrunesOldEntries() {
        val now = 100 * day
        val list = listOf(entry("old", now - Journal.KEEP_DAYS * day - 1), entry("recent", now - day))
        assertEquals(listOf("new", "recent"), Journal.add(list, entry("new", now)).map { it.recordId })
    }

    @Test
    fun betweenIsHalfOpen() {
        val list = listOf(entry("before", 999), entry("start", 1_000), entry("mid", 1_500), entry("end", 2_000))
        assertEquals(listOf("mid", "start"), Journal.between(list, 1_000, 2_000).map { it.recordId })
    }

    @Test
    fun removeById() {
        assertEquals(listOf("b"), Journal.remove(listOf(entry("a", 1), entry("b", 2)), "a").map { it.recordId })
    }

    @Test
    fun totalSumsKnownValues() {
        val total = Journal.total(listOf(entry("a", 1, kcal = 100.0), entry("b", 2, kcal = 50.0, protein = 3.0)))
        assertEquals(150.0, total.kcal!!, 1e-9)
        assertEquals(3.0, total.protein!!, 1e-9)
        assertNull(total.fat)
        assertTrue(Journal.total(emptyList()).isEmpty)
    }

    @Test
    fun roundTripAndCorruptInput() {
        val list = listOf(entry("a", 1, protein = 2.5))
        assertEquals(list, Journal.decode(Journal.encode(list)))
        assertEquals(emptyList(), Journal.decode(""))
        assertEquals(emptyList(), Journal.decode("{not json"))
    }
}
