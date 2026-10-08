package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFoodFactsTest {
    private val langs = listOf("de", "fr", "en")

    @Test
    fun urlContainsFields() {
        val url = OpenFoodFacts.productUrl("7610200337313", listOf("de", "fr"))
        assertTrue(url.startsWith("https://world.openfoodfacts.org/api/v2/product/7610200337313.json?fields="))
        val fields = url.substringAfter("fields=").split(",")
        listOf("code", "product_name", "generic_name", "brands", "serving_quantity", "serving_quantity_unit",
            "nutriments", "product_name_de", "product_name_fr").forEach { assertTrue(it in fields, it) }
    }

    @Test
    fun swissProductWithKcal() {
        val json = """
            {"code":"7610200337313","status":1,"status_verbose":"product found","product":{
              "product_name":"Chocolate","product_name_de":"Schokolade","product_name_fr":"Chocolat",
              "brands":" Lindt , Other","serving_quantity":25,"serving_quantity_unit":"g","unknown":{"a":1},
              "nutriments":{"energy-kcal_100g":550,"energy-kj_100g":2300,"fat_100g":32.5,"saturated-fat_100g":19,
              "carbohydrates_100g":55,"sugars_100g":50,"fiber_100g":2,"proteins_100g":6.5,"salt_100g":0.1}}}
        """
        val p = assertNotNull(OpenFoodFacts.parse("7610200337313", json, langs))
        assertEquals("Schokolade", p.name)
        assertEquals("Lindt", p.brand)
        assertEquals(25.0, p.servingGrams)
        assertEquals(Nutrients(550.0, 32.5, 19.0, 55.0, 50.0, 2.0, 6.5, 0.1), p.per100g)
        assertEquals(OpenFoodFacts.SOURCE, p.source)
    }

    @Test
    fun languageOrderAndFallbacks() {
        val fr = """{"status":1,"product":{"product_name":"Generic","product_name_de":"  ","product_name_fr":"Chocolat"}}"""
        assertEquals("Chocolat", OpenFoodFacts.parse("1", fr, langs)?.name)
        val plain = """{"status":1,"product":{"product_name":" Plain ","generic_name":"G"}}"""
        assertEquals("Plain", OpenFoodFacts.parse("1", plain, langs)?.name)
        val generic = """{"status":1,"product":{"product_name":"","generic_name":"G"}}"""
        assertEquals("G", OpenFoodFacts.parse("1", generic, langs)?.name)
        val none = """{"status":1,"product":{"brands":"  "}}"""
        val p = assertNotNull(OpenFoodFacts.parse("42", none, langs))
        assertEquals("Product 42", p.name)
        assertNull(p.brand)
        assertTrue(p.per100g.isEmpty)
        assertNull(p.servingGrams)
    }

    @Test
    fun kilojouleFallbacks() {
        val kj = """{"status":1,"product":{"nutriments":{"energy-kj_100g":"1046"}}}"""
        assertEquals(250.0, OpenFoodFacts.parse("1", kj, langs)!!.per100g.kcal!!, 1e-6)
        val energy = """{"status":1,"product":{"nutriments":{"energy_100g":418.4}}}"""
        assertEquals(100.0, OpenFoodFacts.parse("1", energy, langs)!!.per100g.kcal!!, 1e-6)
    }

    @Test
    fun numbersAsStringsAndSodiumOnly() {
        val json = """{"status":1,"product":{"serving_quantity":"30","serving_quantity_unit":"ML",
            "nutriments":{"energy-kcal_100g":"123.5","fat_100g":"1.5","proteins_100g":"-3","sugars_100g":"abc",
            "carbohydrates_100g":null,"sodium_100g":"0.4"}}}"""
        val p = OpenFoodFacts.parse("1", json, langs)!!
        assertEquals(123.5, p.per100g.kcal)
        assertEquals(1.5, p.per100g.fat)
        assertNull(p.per100g.protein)
        assertNull(p.per100g.sugar)
        assertNull(p.per100g.carbs)
        assertEquals(1.0, p.per100g.salt!!, 1e-9)
        assertEquals(30.0, p.servingGrams)
    }

    @Test
    fun saltPreferredOverSodium() {
        val json = """{"status":1,"product":{"nutriments":{"salt_100g":1.2,"sodium_100g":9}}}"""
        assertEquals(1.2, OpenFoodFacts.parse("1", json, langs)!!.per100g.salt)
    }

    @Test
    fun servingRules() {
        fun serving(extra: String) =
            OpenFoodFacts.parse("1", """{"status":1,"product":{$extra}}""", langs)!!.servingGrams
        assertEquals(40.0, serving(""""serving_quantity":40"""))
        assertNull(serving(""""serving_quantity":40,"serving_quantity_unit":"oz""""))
        assertNull(serving(""""serving_quantity":0"""))
        assertNull(serving(""""serving_quantity":"x""""))
    }

    @Test
    fun packageRules() {
        fun pack(extra: String) =
            OpenFoodFacts.parse("1", """{"status":1,"product":{$extra}}""", langs)!!.packageGrams
        assertEquals(200.0, pack(""""product_quantity":"200","product_quantity_unit":"g""""))
        assertEquals(500.0, pack(""""product_quantity":500,"product_quantity_unit":"ML""""))
        assertEquals(250.0, pack(""""product_quantity":250"""))
        assertNull(pack(""""product_quantity":2,"product_quantity_unit":"kg""""))
        assertNull(pack(""""product_quantity":0"""))
        assertNull(pack(""))
        assertTrue(OpenFoodFacts.productUrl("1", langs).contains("product_quantity"))
    }

    @Test
    fun missingProduct() {
        assertNull(OpenFoodFacts.parse("1", """{"code":"1","status":0,"status_verbose":"product not found"}""", langs))
        assertNull(OpenFoodFacts.parse("1", """{"status":1}""", langs))
        assertNull(OpenFoodFacts.parse("1", """{"status":1,"product":"x"}""", langs))
    }

    @Test
    fun malformedJson() {
        assertNull(OpenFoodFacts.parse("1", "", langs))
        assertNull(OpenFoodFacts.parse("1", "<html>502</html>", langs))
        assertNull(OpenFoodFacts.parse("1", """{"status":1,"product":{""", langs))
        assertNull(OpenFoodFacts.parse("1", "[1,2]", langs))
    }
}

class OpenFoodFactsSearchTest {
    private val langs = listOf("de", "fr")

    @Test
    fun searchUrl() {
        val url = OpenFoodFacts.searchUrl(" Caffè Latte ", langs)
        assertTrue(url.startsWith("https://world.openfoodfacts.org/cgi/search.pl?"))
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Caffè Latte", params["search_terms"])
        assertEquals("countries", params["tagtype_0"])
        assertEquals("switzerland", params["tag_0"])
        assertEquals("unique_scans_n", params["sort_by"])
        assertEquals("1", params["json"])
        val fields = params.getValue("fields").split(",")
        listOf("code", "nutriments", "quantity", "stores", "brands", "product_name_de").forEach { assertTrue(it in fields, it) }
    }

    @Test
    fun parseSearchKeepsOrderAndPutsMissingNutritionLast() {
        val json = """
            {"count":3,"page":1,"products":[
              {"code":"111","product_name":"Joghurt","quantity":"180 g"},
              {"code":"222","product_name_de":"Joghurt Erdbeer","brands":"M-Budget, Migros","stores":"Migros","quantity":"500 g",
               "nutriments":{"energy-kcal_100g":95}},
              {"product_name":"No code"},
              {"code":"333","product_name":"Yogourt","brands":"Coop","nutriments":{"energy-kj_100g":418.4}},
              {"code":"222","product_name":"Duplicate"}
            ]}
        """
        val hits = OpenFoodFacts.parseSearch(json, langs)
        assertEquals(listOf("222", "333", "111"), hits.map { it.product.barcode })
        val first = hits.first()
        assertEquals("Joghurt Erdbeer", first.product.name)
        assertEquals("M-Budget", first.product.brand)
        assertEquals("M-Budget, Migros", first.brands)
        assertEquals("Migros", first.stores)
        assertEquals("500 g", first.quantity)
        assertEquals(100.0, hits[1].product.per100g.kcal!!, 0.001)
        assertTrue(!hits.last().hasNutrition)
    }

    @Test
    fun parseSearchToleratesBadInput() {
        assertEquals(emptyList(), OpenFoodFacts.parseSearch("", langs))
        assertEquals(emptyList(), OpenFoodFacts.parseSearch("""{"products":"x"}""", langs))
        assertEquals(emptyList(), OpenFoodFacts.parseSearch("""{"count":0}""", langs))
    }
}
