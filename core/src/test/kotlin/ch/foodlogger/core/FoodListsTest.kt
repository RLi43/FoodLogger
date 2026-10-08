package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun product(i: Int, name: String = "P$i", brand: String? = null) =
    Product("$i", name, brand = brand, per100g = Nutrients(kcal = i.toDouble()), source = "Manual")

private const val DAY = 24 * 60 * 60 * 1000L

class FoodHistoryTest {
    private val now = 1_000 * DAY

    @Test
    fun recordCountsAndReplaces() {
        var list = FoodHistory.record(emptyList(), product(1), now - 2)
        list = FoodHistory.record(list, product(2), now - 1)
        list = FoodHistory.record(list, product(1).copy(name = "New"), now)
        assertEquals(listOf("1", "2"), list.map { it.product.barcode })
        assertEquals(2, list.first().count)
        assertEquals("New", list.first().product.name)
        assertEquals(now, list.first().lastUsedMillis)
    }

    @Test
    fun frequentBeatsOnceButFadesWithAge() {
        val often = HistoryEntry(product(1), count = 5, lastUsedMillis = now - 7 * DAY)
        val once = HistoryEntry(product(2), count = 1, lastUsedMillis = now)
        assertEquals(listOf("1", "2"), FoodHistory.sorted(listOf(once, often), now).map { it.product.barcode })
        val longAgo = often.copy(lastUsedMillis = now - 120 * DAY)
        assertEquals(listOf("2", "1"), FoodHistory.sorted(listOf(longAgo, once), now).map { it.product.barcode })
    }

    @Test
    fun capped() {
        val list = (1..FoodHistory.MAX + 5).fold(emptyList<HistoryEntry>()) { acc, i -> FoodHistory.record(acc, product(i), now + i) }
        assertEquals(FoodHistory.MAX, list.size)
        assertEquals("${FoodHistory.MAX + 5}", list.first().product.barcode)
    }

    @Test
    fun removeAndUpdate() {
        val list = listOf(HistoryEntry(product(1), 3, now), HistoryEntry(product(2), 1, now))
        assertEquals(listOf("2"), FoodHistory.remove(list, "1").map { it.product.barcode })
        val updated = FoodHistory.update(list, product(2, name = "Edited"))
        assertEquals("Edited", updated[1].product.name)
        assertEquals(list[0], updated[0])
    }

    @Test
    fun roundtripAndLegacyRecentList() {
        val list = listOf(HistoryEntry(product(1).copy(brand = "B", servingGrams = 30.0), 4, now), HistoryEntry(product(2), 1, now - DAY))
        assertEquals(list, FoodHistory.decode(FoodHistory.encode(list), now))
        val legacy = """[{"barcode":"1","name":"A","source":"S"},{"barcode":"2","name":"B","source":"S","extra":true}]"""
        val migrated = FoodHistory.decode(legacy, now)
        assertEquals(listOf("1", "2"), migrated.map { it.product.barcode })
        assertTrue(migrated.all { it.count == 1 })
    }

    @Test
    fun decodeToleratesBadInput() {
        assertEquals(emptyList(), FoodHistory.decode("", now))
        assertEquals(emptyList(), FoodHistory.decode("{not json", now))
        assertEquals(emptyList(), FoodHistory.decode("""[{"barcode":1}]""", now))
    }
}

class MyFoodsTest {
    @Test
    fun saveReplacesAndSortsByName() {
        var list = MyFoods.save(emptyList(), product(1, "Zopf"))
        list = MyFoods.save(list, product(2, "Äpfel"))
        list = MyFoods.save(list, product(1, "Birchermüesli"))
        assertEquals(listOf("Äpfel", "Birchermüesli"), list.map { it.name })
        assertEquals(listOf("2"), MyFoods.remove(list, "1").map { it.barcode })
    }

    @Test
    fun roundtrip() {
        val list = listOf(product(1, brand = "Migros"), product(2))
        assertEquals(list, MyFoods.decode(MyFoods.encode(list)))
        assertEquals(emptyList(), MyFoods.decode(" "))
        assertEquals(emptyList(), MyFoods.decode("nope"))
    }
}

class FoodSearchTest {
    @Test
    fun normalize() {
        assertEquals("caffe latte", FoodSearch.normalize("  Caffè  Latte! "))
        assertEquals("m-budget joghurt erdbeer", FoodSearch.normalize("M-Budget Joghurt, Erdbeer"))
        assertEquals(listOf("creme", "brulee"), FoodSearch.words("Crème brûlée"))
    }

    @Test
    fun matchesEveryWordAcrossNameAndBrand() {
        assertTrue(FoodSearch.matches("emmi caffe", "Caffè Latte Macchiato", "Emmi"))
        assertTrue(FoodSearch.matches("jogh", "Joghurt Nature"))
        assertFalse(FoodSearch.matches("emmi espresso", "Caffè Latte", "Emmi"))
    }

    @Test
    fun soldAtUsesStoreBrands() {
        assertTrue(FoodSearch.soldAt(null, null))
        assertTrue(FoodSearch.soldAt(Store.MIGROS, "M-Classic, Migros"))
        assertTrue(FoodSearch.soldAt(Store.COOP, "Naturaplan", null))
        assertTrue(FoodSearch.soldAt(Store.COOP, null, "Coop City"))
        assertTrue(FoodSearch.soldAt(Store.COOP, "Qualité & Prix"))
        assertFalse(FoodSearch.soldAt(Store.MIGROS, "Emmi", "Coop"))
        assertFalse(FoodSearch.soldAt(Store.LIDL, null, null))
    }

    @Test
    fun localSearch() {
        val mine = listOf(product(1, "Joghurt Erdbeer", "M-Budget"), product(2, "Joghurt Nature", "Naturaplan"), product(3, "Brot"))
        assertEquals(listOf("1", "2"), FoodSearch.local(mine + mine[0], "joghurt", null).map { it.barcode })
        assertEquals(listOf("2"), FoodSearch.local(mine, "joghurt", Store.COOP).map { it.barcode })
        assertEquals(emptyList(), FoodSearch.local(mine, "  ", null))
    }

    @Test
    fun remoteSearchFiltersByStoreAndLimits() {
        fun hit(i: Int, brands: String?, stores: String?) = SearchHit(product(i), null, brands, stores)
        val hits = listOf(hit(1, "Emmi", "Migros, Coop"), hit(2, "M-Classic", null), hit(3, "Naturaplan", null))
        assertEquals(listOf("1", "2"), FoodSearch.remote(hits, Store.MIGROS).map { it.product.barcode })
        assertEquals(listOf("1", "2"), FoodSearch.remote(hits, null, limit = 2).map { it.product.barcode })
    }
}
