package ch.foodlogger.core

import java.text.Normalizer

/** A line of recognised text with its bounding box in image pixels (y grows downwards). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Values read from a nutrition label: per 100 g (or 100 ml), and the serving size if the label prints one. */
data class LabelScan(val per100g: Nutrients, val servingGrams: Double? = null) {
    /** Number of nutrient values that were found (energy counts as one). */
    val valueCount: Int
        get() = with(per100g) { listOf(kcal, fat, saturatedFat, carbs, sugar, fiber, protein, salt).count { it != null } }
}

/**
 * Reads the nutrition table of a food label from OCR text, in German, French, Italian or English
 * (Swiss labels often print all of them in one row: "Fett / Matières grasses / Grassi 12 g").
 *
 * Takes the per-100 g column, which EU and Swiss labels must print and usually print first;
 * when the column header names the portion before "100 g", the second column is used.
 * Energy is taken in kcal, or converted from kJ when only kJ is readable.
 */
object NutritionLabel {

    /**
     * Groups OCR lines into table rows: lines whose vertical centres are close are joined left to right,
     * so a nutrient name and its values printed in separate columns end up in one row of text.
     */
    fun rows(lines: List<OcrLine>): List<String> {
        class Row(val centre: Double, val height: Int, val lines: MutableList<OcrLine>)
        val rows = mutableListOf<Row>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.top + it.bottom }) {
            val centre = (line.top + line.bottom) / 2.0
            val height = line.bottom - line.top
            val row = rows.lastOrNull { kotlin.math.abs(it.centre - centre) < maxOf(it.height, height) / 2.0 }
            if (row != null) row.lines += line else rows += Row(centre, height, mutableListOf(line))
        }
        return rows.map { row -> row.lines.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /** Parses OCR lines with their positions; see [rows]. */
    fun parseLines(lines: List<OcrLine>): LabelScan = parse(rows(lines))

    /** Parses plain text, one table row per line. */
    fun parse(text: String): LabelScan = parse(text.lines())

    /** Parses table rows of text, e.g. "Fett / Matières grasses 12,5 g 3,8 g 18 %". */
    fun parse(rows: List<String>): LabelScan {
        val normalized = rows.map(::normalize).filter { it.isNotBlank() }
        val column = per100gColumn(normalized)
        val values = mutableMapOf<Field, Double>()
        var kcal: Double? = null
        var kj: Double? = null
        // A nutrient whose name had no value in its row; the next row may hold only the value.
        var pending: Field? = null

        for (row in normalized) {
            val field = Field.entries.firstOrNull { it.pattern.containsMatchIn(row) }
            if (field == null && skip.containsMatchIn(row)) {
                pending = null
                continue
            }
            val target = field ?: pending?.takeIf { isMostlyNumbers(row) }
            if (target == null) {
                pending = null
                continue
            }
            if (target == Field.ENERGY) {
                val energy = energy(row, column)
                if (kcal == null) kcal = energy.kcal
                if (kj == null) kj = energy.kj
                // kJ and kcal are often printed on two rows below one "Energie" heading.
                pending = Field.ENERGY.takeIf { energy.kcal == null }
                continue
            }
            val amount = grams(row, column)
            if (amount != null) {
                if (target !in values) values[target] = amount
                pending = null
            } else {
                pending = target
            }
        }

        val energyKcal = (kcal ?: kj?.div(KJ_PER_KCAL) ?: fallbackKcal(normalized))?.takeIf { it <= 900 }
        val fat = values[Field.FAT]
        val carbs = values[Field.CARBS]
        return LabelScan(
            per100g = Nutrients(
                kcal = energyKcal,
                fat = fat,
                saturatedFat = values[Field.SATURATED]?.takeIf { fat == null || it <= fat },
                carbs = carbs,
                sugar = values[Field.SUGAR]?.takeIf { carbs == null || it <= carbs },
                fiber = values[Field.FIBER],
                protein = values[Field.PROTEIN],
                salt = values[Field.SALT] ?: values[Field.SODIUM]?.times(2.5)?.takeIf { it <= 100 },
            ),
            servingGrams = normalized.firstNotNullOfOrNull { serving.find(it) }
                ?.groupValues?.get(2)?.let(::toNumber)?.takeIf { it > 0 && it <= 1000 },
        )
    }

    private const val KJ_PER_KCAL = 4.184

    /** Checked in this order, so "davon gesättigte Fettsäuren" is saturated fat before it could be fat. */
    private enum class Field(vararg words: String) {
        SATURATED("""\bgesattigt""", """\bsatur"""),
        SUGAR("""\bzucker\b""", """\bsugars?\b""", """\bsucres?\b""", """\bzuccheri\b"""),
        FIBER("""\bballaststoff""", """\bnahrungsfaser""", """\bfib(re|er|res|ers)\b"""),
        SODIUM("""\bnatrium\b""", """\bsodium\b""", """\bsodio\b"""),
        SALT("""\bsalz\b""", """\bsalt\b""", """\bsel\b""", """\bsale\b"""),
        FAT("""\bfett\b""", """\bfats?\b""", """\bgrasses\b""", """\blipides\b""", """\bgrassi\b"""),
        CARBS("""\bkohlenhydrat""", """\bcarbohydrate""", """\bglucides\b""", """\bcarboidrati\b"""),
        PROTEIN("""\beiweiss""", """\bprotein""", """\bproteine""", """\bproteines\b"""),
        ENERGY("""\benergie\b""", """\benergy\b""", """\benergia\b""", """\bbrennwert""", """\benergetique""", """\benergetico\b""");

        val pattern = Regex(words.joinToString("|"))
    }

    /** Rows about nutrients the form has no field for, whose names would otherwise match one that it has. */
    private val skip = Regex("""ungesattigt|unsatur|insatur|zuckeralkohol|polyol""")

    private val traces = Regex("""\b(spuren|traces?|tracce)\b""")
    private val serving = Regex("""\b(portion|serving|porzione|stuck|piece|pezzo)\b[^\d]{0,20}(\d+(?:[.,]\d+)?)\s*(g|ml)\b""")
    private val per100 = Regex("""\b100\s*(g|ml)\b""")
    private val portionWord = Regex("""\b(portion|serving|porzione)\b""")
    private val number = Regex("""\d+(?:[.,]\d+)?""")
    private val unitAfter = Regex("""^\s*(kj|kcal|mg|µg|mcg|g|ml|%)(?![a-z])""")
    private val unitBefore = Regex("""(kj|kcal)\s*[:=]?\s*$""")
    private val slashPair = Regex("""(\d+(?:[.,]\d+)?)\s*/\s*(\d+(?:[.,]\d+)?)""")
    private val unitHeader = Regex("""(kj|kcal)\s*/\s*(kj|kcal)""")
    private val footnote = Regex("""referen|riferimento|intake|apport|erwachsen|adult""")

    /** Lower case without accents; also fixes the letter O read in place of a zero next to digits. */
    private fun normalize(row: String): String {
        val plain = Normalizer.normalize(row.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return plain.replace(Regex("""(?<=\d)o|(?<=\d[.,])o|(?<![a-z])o(?=[.,]\d)"""), "0")
    }

    /** 0 unless a column header says the portion comes before 100 g, e.g. "pro Portion (30 g) | pro 100 g". */
    private fun per100gColumn(rows: List<String>): Int {
        val header = rows.firstOrNull { row -> per100.containsMatchIn(row) && Field.entries.none { it.pattern.containsMatchIn(row) } }
            ?: return 0
        val hundred = per100.find(header)!!.range.first
        val portion = portionWord.find(header)?.range?.first ?: return 0
        return if (portion < hundred) 1 else 0
    }

    private class Amount(val value: Double, val unit: String?)

    /** Numbers in [row] with the unit printed after them; percentages and numbers inside words (B12, omega-3) are left out. */
    private fun amounts(row: String): List<Amount> = number.findAll(row).mapNotNull { match ->
        val before = row.getOrNull(match.range.first - 1)
        if (before != null && (before.isLetter() || before == '-')) return@mapNotNull null
        val unit = unitAfter.find(row.substring(match.range.last + 1))?.groupValues?.get(1)
        if (unit == "%") return@mapNotNull null
        Amount(toNumber(match.value) ?: return@mapNotNull null, unit)
    }.toList()

    /** The value in grams in the per-100 g column, if any. */
    private fun grams(row: String, column: Int): Double? {
        val values = amounts(row).filter { it.unit == null || it.unit == "g" || it.unit == "mg" }
            .map { if (it.unit == "mg") it.value / 1000 else it.value }
        val value = values.getOrNull(column) ?: values.singleOrNull()
            ?: return if (traces.containsMatchIn(row)) 0.0 else null
        return value.takeIf { it <= 100 }
    }

    private class Energy(val kcal: Double?, val kj: Double?)

    private fun energy(rawRow: String, column: Int): Energy {
        // "1 500 kJ" and "1.500 kJ" use a thousands separator.
        val row = rawRow.replace(Regex("""(?<![\d.,])(\d)[\s'’.](\d{3})(?=\s*kj)"""), "$1$2")

        // "Energie kJ/kcal 1500/359 450/108"
        val header = unitHeader.find(row)
        val pairs = slashPair.findAll(row).toList()
        if (header != null && pairs.isNotEmpty()) {
            val pair = pairs.getOrNull(column) ?: pairs.first()
            val (a, b) = pair.groupValues.drop(1).map(::toNumber)
            return if (header.groupValues[1] == "kj") Energy(b, a) else Energy(a, b)
        }

        val numbers = number.findAll(row).toList()
        val first = numbers.firstOrNull() ?: return Energy(null, null)
        // "kJ 1500 kcal 359": the unit comes before each number.
        val unitFirst = unitBefore.containsMatchIn(row.substring(0, first.range.first))
        val tagged = numbers.map { match ->
            val unit = if (unitFirst) {
                unitBefore.find(row.substring(0, match.range.first))?.groupValues?.get(1)
            } else {
                unitAfter.find(row.substring(match.range.last + 1))?.groupValues?.get(1)
            }
            unit to toNumber(match.value)
        }
        fun pick(unit: String) = tagged.filter { it.first == unit }.mapNotNull { it.second }.let { it.getOrNull(column) ?: it.firstOrNull() }
        val kcal = pick("kcal")
        val kj = pick("kj")
        if (kcal != null || kj != null) return Energy(kcal, kj)

        // "Energie (kcal) 359 108": the unit is only named once.
        val plain = tagged.filter { it.first == null }.mapNotNull { it.second }
        val value = plain.getOrNull(column) ?: plain.firstOrNull()
        return when {
            "kcal" in row && "kj" !in row -> Energy(value, null)
            "kj" in row && "kcal" !in row -> Energy(null, value)
            else -> Energy(null, null)
        }
    }

    /** When no row is recognisably about energy, the first "359 kcal" outside a reference-intake footnote. */
    private fun fallbackKcal(rows: List<String>): Double? = rows.asSequence()
        .filterNot { footnote.containsMatchIn(it) }
        .flatMap { amounts(it).asSequence() }
        .firstOrNull { it.unit == "kcal" }?.value

    /** True for a row that is mostly numbers and units, like "12,5 g 3,8 g 18 %". */
    private fun isMostlyNumbers(row: String): Boolean =
        amounts(row).isNotEmpty() && row.replace(Regex("""\b(kj|kcal|mg|g|ml)\b"""), "").count { it.isLetter() } <= 3

    private fun toNumber(text: String): Double? = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}
