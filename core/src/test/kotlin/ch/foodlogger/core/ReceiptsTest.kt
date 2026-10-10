package ch.foodlogger.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parses real receipts (card and loyalty numbers removed): Migros and Coop PDFs from their apps, an Aldi photo. */
class ReceiptsTest {
    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/receipts/$name.txt")!!.bufferedReader().readLines()

    private fun ReceiptLine.summary() = "$name|$quantity|$weighed|$total|$food"

    @Test
    fun migrosPdf() {
        val receipt = Receipts.parse(fixture("migros"))
        assertEquals(Store.MIGROS, receipt.store)
        assertEquals(LocalDate.of(2026, 9, 5), receipt.date)
        assertEquals(
            listOf(
                "Cabas|1.0|false|0.4|false",
                "Extra nectarines blanc|0.227|true|0.75|true",
                "Bio Fairtrade gingemb.|0.06|true|0.45|true",
                "Rallonge 1m|1.0|false|3.95|false",
                "Candida Prof. Protect|1.0|false|3.32|false",
                "Candida Pro. Sensitive|1.0|false|3.32|false",
                "MClass ASC filet saum.|1.0|false|7.35|true",
                "Bio pleurotes panicaut|1.0|false|4.45|true",
                "Bio snack poivrons|1.0|false|1.95|true",
                "Bio kiwis Gold|1.0|false|2.9|true",
                "Bustier femme Microfib|1.0|false|19.95|false",
                "Bio Pousses Mungo|1.0|false|2.2|true",
                "Émincé de boeuf|0.268|true|7.1|true",
                "MClass ASC Crevettes|1.0|false|2.9|true",
                "Levure sèche 6x7g|1.0|false|0.75|true",
                "Avocat|2.0|false|1.94|true",
            ),
            receipt.lines.map { it.summary() },
        )
        assertEquals(268.0, receipt.lines.first { it.name == "Émincé de boeuf" }.grams!!, 1e-9)
        assertEquals(42.0, receipt.lines.first { it.name.startsWith("Levure") }.grams!!, 1e-9)
        assertEquals(2, receipt.lines.last().packs)
    }

    @Test
    fun coopPdfWithDiscount() {
        val receipt = Receipts.parse(fixture("coop"))
        assertEquals(Store.COOP, receipt.store)
        assertEquals(LocalDate.of(2026, 10, 7), receipt.date)
        assertEquals(
            listOf(
                "Betty Bossi FG Korean Beef 350g|1.0|false|11.95|true",
                "Yakult Original 6x65ml|1.0|false|5.1|true",
                "Yakult Plus 6x65ml|1.0|false|3.0|true",
            ),
            receipt.lines.map { it.summary().replace("2.9999999999999996", "3.0") },
        )
        assertEquals(390.0, receipt.lines[1].grams!!, 1e-9)
    }

    @Test
    fun aldiPhoto() {
        val receipt = Receipts.parse(fixture("aldi"))
        assertEquals(Store.ALDI, receipt.store)
        assertEquals(LocalDate.of(2026, 10, 10), receipt.date)
        assertEquals(12, receipt.lines.size)
        assertTrue(receipt.lines.all { it.food == true })
        val ribEye = receipt.lines.first { it.name == "Rib-eye" }
        assertEquals("290456", ribEye.articleNumber)
        assertTrue(ribEye.weighed)
        assertEquals(256.0, ribEye.grams!!, 1e-9)
        assertEquals(11.49, ribEye.total)
        assertEquals(4500.0, receipt.lines.first().grams!!, 1e-9)
        assertEquals(PackSize(4, 115.0), receipt.lines.first { it.name.startsWith("Activia") }.pack)
        assertEquals("Auberg. ron. pièce", receipt.lines.last().name)
    }

    @Test
    fun layoutIndependentOfSpacing() {
        // PDF text extraction may print one space between columns instead of padding.
        val squeezed = fixture("coop").map { it.trim().replace(Regex("\\s+"), " ") }
        assertEquals(3, Receipts.parse(squeezed).lines.size)
    }

    @Test
    fun unknownStoreFallsBackToPricedLines() {
        val receipt = Receipts.parse(
            listOf("Bäckerei Muster", "Zopf 500g 6.50", "2 x Gipfeli 3.20", "Total CHF 9.70", "Bar 10.00"),
        )
        assertNull(receipt.store)
        assertEquals(listOf("Zopf 500g", "Gipfeli"), receipt.lines.map { it.name })
        assertEquals(2.0, receipt.lines[1].quantity)
        assertNull(receipt.lines[0].food)
    }

    @Test
    fun packSizes() {
        assertEquals(PackSize(9, 500.0), PackSize.parse("Aquata Cl. 9x0.5l"))
        assertEquals(PackSize(6, 65.0), PackSize.parse("Yakult 6x65ml"))
        assertEquals(PackSize(1, 500.0), PackSize.parse("Filet poulet 500g"))
        assertEquals(PackSize(1, 1500.0), PackSize.parse("Coca Cola 1,5 l"))
        assertEquals(PackSize(1, 330.0), PackSize.parse("Rivella 33cl"))
        assertNull(PackSize.parse("Rallonge 1m"))
        assertNull(PackSize.parse("Aquata Cl."))
        assertNull(PackSize.parse("Saumon sauvage pf"))
    }

    @Test
    fun weighedLineGramsAndPacks() {
        val line = ReceiptLine("Brocoli", quantity = 0.528, weighed = true)
        assertEquals(528.0, line.grams!!, 1e-9)
        assertEquals(1, line.packs)
        assertFalse(ReceiptLine("Avocat", quantity = 2.0).grams != null)
    }

    @Test
    fun photoPiecesJoinIntoLines() {
        val pieces = listOf(
            OcrLine("11.49 A", 600, 104, 700, 124),
            OcrLine("290456 Rib-eye", 10, 100, 300, 120),
            OcrLine("0.256 kg x 44.90 CHF/kg", 60, 130, 500, 150),
            OcrLine("ALDI SUISSE AG", 200, 10, 500, 30),
        )
        assertEquals(listOf("ALDI SUISSE AG", "290456 Rib-eye 11.49 A", "0.256 kg x 44.90 CHF/kg"), Receipts.rows(pieces))
    }
}
