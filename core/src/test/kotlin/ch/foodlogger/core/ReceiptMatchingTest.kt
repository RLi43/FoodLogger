package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptMatchingTest {
    private val salmon = Product("761", "Filet de saumon ASC", brand = "M-Classic", packageGrams = 250.0, source = "Open Food Facts")
    private val yakult = Product("762", "Yakult Original", brand = "Yakult", packageGrams = 390.0, source = "Open Food Facts")
    private val activia = Product("763", "Activia Nature", brand = "Danone", packageGrams = 115.0, source = "Open Food Facts")
    private val beef = Product("764", "Korean Beef", brand = "Betty Bossi", packageGrams = 500.0, source = "Open Food Facts")

    @Test
    fun queryDropsPackSizesAndNoiseAndExpandsShorthand() {
        assertEquals("m-classic filet saumon", ReceiptMatching.query("MClass ASC filet saum."))
        assertEquals("yakult original", ReceiptMatching.query("Yakult Original 6x65ml"))
        assertEquals("kiwis gold", ReceiptMatching.query("Bio kiwis Gold"))
        assertEquals("avocat", ReceiptMatching.query("Avocat bio piece"))
    }

    @Test
    fun keysUseArticleNumbersWhenPrinted() {
        assertEquals("ALDI:#290456", ReceiptMatching.key(Store.ALDI, ReceiptLine("Rib-eye", articleNumber = "290456")))
        assertEquals("MIGROS:mclass asc filet saum", ReceiptMatching.key(Store.MIGROS, ReceiptLine("MClass ASC filet saum.")))
        assertEquals("ANY:zopf", ReceiptMatching.key(null, ReceiptLine("Zopf")))
    }

    @Test
    fun scoreAcceptsShortenedWords() {
        val line = ReceiptLine("MClass ASC filet saum.")
        assertEquals(1.0, ReceiptMatching.score(line, salmon))
        assertTrue(ReceiptMatching.confident(line, salmon))
        assertEquals(0.0, ReceiptMatching.score(line, yakult))
    }

    @Test
    fun packSizeMustAgree() {
        assertTrue(ReceiptMatching.confident(ReceiptLine("Yakult Original 6x65ml"), yakult))
        // One cup of a four-pack is also a fair pack size.
        assertFalse(ReceiptMatching.packDisagrees(ReceiptLine("Activia 4x115g"), activia))
        assertTrue(ReceiptMatching.packDisagrees(ReceiptLine("Betty Bossi FG Korean Beef 350g"), beef))
        assertFalse(ReceiptMatching.confident(ReceiptLine("Betty Bossi FG Korean Beef 350g"), beef))
    }

    @Test
    fun guessesNeedHalfTheWords() {
        val guesses = ReceiptMatching.guesses(ReceiptLine("Yakult Plus 6x65ml"), listOf(salmon, yakult, activia))
        assertEquals(listOf(yakult), guesses)
        assertTrue(ReceiptMatching.guesses(ReceiptLine("Rallonge 1m"), listOf(salmon, yakult)).isEmpty())
    }

    @Test
    fun rememberReplacesAndRoundTrips() {
        var matches = ReceiptMatching.remember(emptyList(), "COOP:yakult", activia)
        matches = ReceiptMatching.remember(matches, "COOP:yakult", yakult)
        matches = ReceiptMatching.remember(matches, "ALDI:#1", salmon)
        assertEquals(2, matches.size)
        val decoded = ReceiptMatching.decode(ReceiptMatching.encode(matches))
        assertEquals(yakult, ReceiptMatching.find(decoded, "COOP:yakult"))
        assertNull(ReceiptMatching.find(decoded, "COOP:other"))
        assertTrue(ReceiptMatching.decode("not json").isEmpty())
    }

    @Test
    fun placeholderCarriesPackSize() {
        val product = ReceiptMatching.placeholder(Store.COOP, ReceiptLine("Yakult Plus 6x65ml", quantity = 2.0))
        assertTrue(ReceiptMatching.needsMatch(product))
        assertEquals(390.0, product.packageGrams!!, 1e-9)
        assertEquals("Coop", product.brand)
    }
}
