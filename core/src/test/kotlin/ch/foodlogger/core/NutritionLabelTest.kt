package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NutritionLabelTest {

    private fun assertClose(expected: Double?, actual: Double?, message: String? = null) {
        if (expected == null) return assertNull(actual, message)
        assertEquals(expected, actual ?: error("${message ?: "value"} is null"), 0.01, message)
    }

    @Test
    fun swissTrilingualLabel() {
        val scan = NutritionLabel.parse(
            """
            Nährwerte / Valeurs nutritives / Valori nutritivi   pro 100 g   pro Portion (30 g)   %*
            Energie / Énergie / Energia   1 571 kJ / 374 kcal   471 kJ / 112 kcal   6 %
            Fett / Matières grasses / Grassi   12 g   3,6 g   5 %
            davon gesättigte Fettsäuren / dont acides gras saturés / di cui acidi grassi saturi   5,1 g   1,5 g   8 %
            Kohlenhydrate / Glucides / Carboidrati   58 g   17 g   7 %
            davon Zucker / dont sucres / di cui zuccheri   21 g   6,3 g   7 %
            Ballaststoffe / Fibres alimentaires / Fibre   4,5 g   1,4 g
            Eiweiss / Protéines / Proteine   6,8 g   2 g   4 %
            Salz / Sel / Sale   0,45 g   0,14 g   2 %
            * Referenzmenge für einen durchschnittlichen Erwachsenen (8400 kJ / 2000 kcal)
            """.trimIndent(),
        )
        assertEquals(Nutrients(374.0, 12.0, 5.1, 58.0, 21.0, 4.5, 6.8, 0.45), scan.per100g)
        assertEquals(30.0, scan.servingGrams)
        assertEquals(8, scan.valueCount)
    }

    @Test
    fun englishLabel() {
        val scan = NutritionLabel.parse(
            """
            Nutrition Information   Per 100g   Per serving (25g)
            Energy   1985kJ / 474kcal   496kJ / 119kcal
            Fat   23.9g   6.0g
            of which saturates   14.2g   3.6g
            of which mono-unsaturates   7.1g   1.8g
            Carbohydrate   58.1g   14.5g
            of which sugars   48.3g   12.1g
            Fibre   2.1g   0.5g
            Protein   6.4g   1.6g
            Salt   0.24g   0.06g
            """.trimIndent(),
        )
        assertEquals(Nutrients(474.0, 23.9, 14.2, 58.1, 48.3, 2.1, 6.4, 0.24), scan.per100g)
        assertEquals(25.0, scan.servingGrams)
    }

    @Test
    fun frenchLabelWithKjAndKcalOnSeparateRows() {
        val scan = NutritionLabel.parse(
            """
            Valeurs nutritionnelles moyennes pour 100 g
            Énergie   1 046 kJ
                      250 kcal
            Matières grasses   9,0 g
            dont acides gras saturés   2,5 g
            Glucides   33 g
            dont sucres   3,0 g
            Fibres alimentaires   2,7 g
            Protéines   8,2 g
            Sel   1,1 g
            """.trimIndent(),
        )
        assertEquals(Nutrients(250.0, 9.0, 2.5, 33.0, 3.0, 2.7, 8.2, 1.1), scan.per100g)
        assertNull(scan.servingGrams)
    }

    @Test
    fun italianLabelOnlyInKj() {
        val scan = NutritionLabel.parse(
            """
            Valori nutrizionali per 100 g
            Energia 1674 kJ
            Grassi 1,5 g
            di cui acidi grassi saturi 0,3 g
            Carboidrati 72 g
            di cui zuccheri 3,5 g
            Proteine 12,5 g
            Sale 0,01 g
            """.trimIndent(),
        )
        assertClose(1674 / 4.184, scan.per100g.kcal)
        assertEquals(1.5, scan.per100g.fat)
        assertEquals(0.3, scan.per100g.saturatedFat)
        assertEquals(72.0, scan.per100g.carbs)
        assertEquals(3.5, scan.per100g.sugar)
        assertNull(scan.per100g.fiber)
        assertEquals(12.5, scan.per100g.protein)
        assertEquals(0.01, scan.per100g.salt)
    }

    @Test
    fun germanBrennwertWithUnitHeader() {
        val scan = NutritionLabel.parse(
            """
            Durchschnittliche Nährwerte   je 100 ml
            Brennwert kJ/kcal   272/65
            Fett   3,5 g
            davon gesättigte Fettsäuren   2,3 g
            Kohlenhydrate   4,8 g
            davon Zucker   4,8 g
            Eiweiß   3,4 g
            Salz   0,13 g
            """.trimIndent(),
        )
        assertEquals(Nutrients(65.0, 3.5, 2.3, 4.8, 4.8, null, 3.4, 0.13), scan.per100g)
    }

    @Test
    fun unitBeforeNumbers() {
        val scan = NutritionLabel.parse("Energie kJ 1500 kcal 359\nFett 12 g")
        assertEquals(359.0, scan.per100g.kcal)
        assertEquals(12.0, scan.per100g.fat)
    }

    @Test
    fun portionColumnFirst() {
        val scan = NutritionLabel.parse(
            """
            pro Portion (40 g)   pro 100 g
            Energie   600 kJ / 143 kcal   1500 kJ / 357 kcal
            Fett   2 g   5 g
            Eiweiss   4 g   10 g
            """.trimIndent(),
        )
        assertEquals(357.0, scan.per100g.kcal)
        assertEquals(5.0, scan.per100g.fat)
        assertEquals(10.0, scan.per100g.protein)
        assertEquals(40.0, scan.servingGrams)
    }

    @Test
    fun stackedLanguagesWithValueOnLastLine() {
        val scan = NutritionLabel.parse(
            """
            Kohlenhydrate
            Glucides
            Carboidrati 50 g
            Eiweiss
            12 g
            """.trimIndent(),
        )
        assertEquals(50.0, scan.per100g.carbs)
        assertEquals(12.0, scan.per100g.protein)
    }

    @Test
    fun ocrQuirks() {
        val scan = NutritionLabel.parse(
            """
            Fett <O,5 g
            davon gesättigte Fettsäuren Spuren
            Zucker 1.2g 3%
            Natrium 0,4 g
            Energie 1.500 kJ
            """.trimIndent(),
        )
        assertEquals(0.5, scan.per100g.fat)
        assertEquals(0.0, scan.per100g.saturatedFat)
        assertEquals(1.2, scan.per100g.sugar)
        assertClose(1.0, scan.per100g.salt, "salt from sodium")
        assertClose(1500 / 4.184, scan.per100g.kcal)
    }

    @Test
    fun ingredientsAndImplausibleValuesAreIgnored() {
        val scan = NutritionLabel.parse(
            """
            Zutaten: Zucker, Kakaobutter, Vollmilchpulver 18%, Haselnüsse 5%, Salz
            Fett 129
            Salz 0,2 g
            """.trimIndent(),
        )
        // "12 g" read as "129" is more than 100 g per 100 g, so it is dropped rather than guessed.
        assertNull(scan.per100g.fat)
        assertNull(scan.per100g.sugar)
        assertEquals(0.2, scan.per100g.salt)
    }

    @Test
    fun nothingReadable() {
        val scan = NutritionLabel.parse("Bitte kühl lagern\nMindestens haltbar bis 12.2026")
        assertEquals(Nutrients(), scan.per100g)
        assertEquals(0, scan.valueCount)
    }

    @Test
    fun linesAreGroupedIntoRowsByPosition() {
        // ML Kit typically returns the name column and the value column as separate blocks.
        val lines = listOf(
            OcrLine("Energie", 10, 100, 120, 120),
            OcrLine("Fett", 10, 130, 60, 150),
            OcrLine("Eiweiss", 10, 160, 110, 180),
            OcrLine("1500 kJ / 359 kcal", 300, 102, 500, 122),
            OcrLine("12 g", 300, 128, 360, 148),
            OcrLine("8,5 g", 300, 161, 370, 181),
            OcrLine("pro 100 g", 300, 70, 400, 90),
        )
        assertEquals(listOf("pro 100 g", "Energie 1500 kJ / 359 kcal", "Fett 12 g", "Eiweiss 8,5 g"), NutritionLabel.rows(lines))
        val scan = NutritionLabel.parseLines(lines)
        assertEquals(Nutrients(kcal = 359.0, fat = 12.0, protein = 8.5), scan.per100g)
    }
}
