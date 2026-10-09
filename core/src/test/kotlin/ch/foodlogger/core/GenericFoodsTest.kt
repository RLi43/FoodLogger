package ch.foodlogger.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericFoodsTest {
    private val en = listOf("en")

    private fun food(id: Int, name: String, category: String? = null, de: String? = null, synonyms: String? = null) = GenericFood(
        id = id,
        names = listOfNotNull("en" to name, de?.let { "de" to it }).toMap(),
        synonyms = synonyms?.let { mapOf("en" to it) } ?: emptyMap(),
        category = category,
        per100g = Nutrients(kcal = id.toDouble()),
    )

    private val foods = listOf(
        food(377, "Pineapple, fresh", "Fruit/Fresh fruit"),
        food(568, "Apple juice", "Fruit/Fruit juices"),
        food(627, "Applesauce, sweetened, canned", "Fruit/Canned fruit"),
        food(378, "Apple, fresh", "Fruit/Fresh fruit", de = "Apfel, frisch"),
        food(823, "White bread", "Bread, flakes and breakfast cereals/Bread", synonyms = "Toast"),
        food(1198, "Birchermüesli, prepared", "Prepared dishes/Muesli and pudding"),
    )

    @Test
    fun ranksNameStartsBeforeContains() {
        assertEquals(listOf(378, 568, 627, 377), GenericFoods.search(foods, "apple", en).map { it.id })
        assertEquals(listOf(378), GenericFoods.search(foods, "apple fresh", en).map { it.id }.take(1))
    }

    @Test
    fun matchesOtherLanguagesSynonymsCategoriesAndAccents() {
        assertEquals(listOf(378), GenericFoods.search(foods, "Apfel", en).map { it.id })
        assertEquals(listOf(823), GenericFoods.search(foods, "toast", en).map { it.id })
        assertEquals(listOf(1198), GenericFoods.search(foods, "birchermuesli", en).map { it.id })
        assertTrue(GenericFoods.search(foods, "fruit", en).size == 4)
        assertEquals(emptyList(), GenericFoods.search(foods, "  ", en))
    }

    @Test
    fun namesAndProducts() {
        val apple = foods.first { it.id == 378 }
        assertEquals("Apfel, frisch", apple.name(listOf("de", "en")))
        assertEquals("Apple, fresh", apple.name(listOf("fr")))
        val product = apple.toProduct(listOf("de"))
        assertEquals("sfcd-378", product.barcode)
        assertEquals(GenericFoods.SOURCE, product.source)
        assertEquals(150.0, product.servingGrams)
        assertTrue(GenericFoods.isGeneric(product))
        assertEquals(null, foods.first { it.id == 568 }.toProduct(en).servingGrams)
    }

    @Test
    fun parsesConverterOutputAndToleratesBadInput() {
        val text = """{"source":"S V 7.1","foods":[{"id":1,"names":{"en":"Veal, breast, raw"},"category":"Meat",
            "per100g":{"kcal":204.0,"fat":14.5,"protein":18.3},"extra":1}]}"""
        val list = GenericFoods.parse(text)
        assertEquals("S V 7.1", list.source)
        assertEquals(Nutrients(kcal = 204.0, fat = 14.5, protein = 18.3), list.foods.single().per100g)
        assertEquals(emptyList(), GenericFoods.parse("nope").foods)
    }

    /** The file shipped in the app: complete, with energy for every food, and the portion IDs exist in it. */
    @Test
    fun bundledFile() {
        val file = File("../app/src/main/assets/generic_foods.json")
        val list = GenericFoods.parse(file.readText())
        assertTrue(list.foods.size > 1000, "${list.foods.size} foods")
        assertTrue(list.foods.all { it.per100g.kcal != null && it.names.isNotEmpty() })
        val ids = list.foods.map { it.id }.toSet()
        assertTrue(GenericFoods.TYPICAL_PORTIONS.keys.all { it in ids }, "portion IDs missing from the database")
        assertEquals(378, GenericFoods.search(list.foods, "apple", en).first().id)
        assertEquals(381, GenericFoods.search(list.foods, "banana", en).first().id)
        val de = listOf("de")
        assertTrue(list.foods.all { "de" in it.names && "fr" in it.names }, "German or French name missing")
        assertEquals(378, GenericFoods.search(list.foods, "Apfel", de).first().id)
        assertEquals(378, GenericFoods.search(list.foods, "pomme", listOf("fr")).first().id)
        assertEquals("Apfel, roh", list.foods.first { it.id == 378 }.name(de))
        assertTrue(GenericFoods.search(list.foods, "Gipfeli", de).isNotEmpty())
    }
}
