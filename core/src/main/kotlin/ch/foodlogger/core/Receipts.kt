package ch.foodlogger.core

import java.time.LocalDate

/**
 * One bought item as printed on a receipt.
 * [quantity] is a count of packs, or kilograms when [weighed] (meat, loose vegetables).
 * [food] is false for non-food lines (VAT above the reduced rate), null when the receipt does not tell.
 * [articleNumber] is the store's own item number when the receipt prints one (Aldi), never a barcode.
 */
data class ReceiptLine(
    val name: String,
    val quantity: Double = 1.0,
    val weighed: Boolean = false,
    val total: Double? = null,
    val food: Boolean? = null,
    val articleNumber: String? = null,
) {
    /** Pack size read from the name, e.g. "Activia 4x115g". */
    val pack: PackSize? get() = PackSize.parse(name)

    /** Grams bought: the weight for weighed items, else packs x pack size; null when the name gives no size. */
    val grams: Double?
        get() = if (weighed) quantity * 1000 else pack?.let { it.grams * quantity }

    /** Number of packs (1 for weighed items). */
    val packs: Int get() = if (weighed) 1 else quantity.toInt().coerceAtLeast(1)
}

/** A receipt read from a PDF or photo; [store] is null when the layout was not recognised. */
data class Receipt(val store: Store?, val date: LocalDate?, val lines: List<ReceiptLine>)

/** Pack size printed in a product name: [count] units of [unitGrams] each (ml counted as grams). */
data class PackSize(val count: Int, val unitGrams: Double) {
    val grams: Double get() = count * unitGrams

    companion object {
        private const val AMOUNT = "(\\d+(?:[.,]\\d+)?)\\s*(kg|g|ml|cl|dl|l)"
        private val MULTI = Regex("(?i)(?<![\\p{L}\\d.,])(\\d+)\\s*[x×]\\s*$AMOUNT(?![\\p{L}])")
        private val SINGLE = Regex("(?i)(?<![\\p{L}\\d.,])$AMOUNT(?![\\p{L}])")

        fun parse(name: String): PackSize? {
            MULTI.find(name)?.let { m ->
                val unit = grams(m.groupValues[2], m.groupValues[3]) ?: return null
                return PackSize(m.groupValues[1].toInt(), unit)
            }
            return SINGLE.find(name)?.let { m -> grams(m.groupValues[1], m.groupValues[2])?.let { PackSize(1, it) } }
        }

        private fun grams(amount: String, unit: String): Double? {
            val value = amount.replace(',', '.').toDoubleOrNull() ?: return null
            val grams = when (unit.lowercase()) {
                "kg", "l" -> value * 1000
                "dl" -> value * 100
                "cl" -> value * 10
                else -> value
            }
            return grams.takeIf { it > 0 }
        }
    }
}

/**
 * Reads the text of a supermarket receipt, as extracted from a PDF or recognised in a photo, one string per
 * printed line. Knows the layouts of Migros and Coop receipts (PDF from their apps or paper) and Aldi Suisse
 * paper receipts; anything else falls back to "name ... price" lines.
 */
object Receipts {
    /** Reduced Swiss VAT rate (food, drinks without alcohol); 2.5 before 2024. */
    private const val REDUCED_VAT_MAX = 3.0

    private const val PRICE = "-?\\d+[.,]\\d{2}"
    private val DATE_TIME = Regex("\\b(\\d{2})\\.(\\d{2})\\.(\\d{4}|\\d{2})\\b\\s*(?:/\\s*)?\\d{2}:\\d{2}")

    private val MIGROS_ITEM = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s+($PRICE)(?:\\s+($PRICE))?\\s+($PRICE)\\s+(\\d)$")
    private val COOP_ITEM = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)(?:\\s*kg)?\\s+($PRICE)(?:\\s+($PRICE))?\\s+($PRICE)\\s+(\\d)(?:\\s+\\p{Lu})?$")
    private val ALDI_ITEM = Regex("^(\\d{5,7})\\s+(.+?)(?:\\s+($PRICE)\\s*(\\p{Lu})?)?$")
    private val ALDI_WEIGHT = Regex("^(\\d+[.,]\\d+)\\s*kg\\s*[x×*]\\s*$PRICE", RegexOption.IGNORE_CASE)
    private val ALDI_COUNT = Regex("^(\\d+)\\s*[x×*]\\s*$PRICE\\s*$", RegexOption.IGNORE_CASE)
    private val DISCOUNT = Regex("^(.+?)\\s+(-\\d+[.,]\\d{2})-?$")
    private val FALLBACK_ITEM = Regex("^(?:(\\d+)\\s*[x×]\\s+)?(.*\\p{L}.*?)\\s+($PRICE)(?:\\s+\\S{1,2})?$")

    /** VAT summary rows: code, rate in %, then amounts ("1 2.60 % 32.74 0.83", "0 2.60 20.05 0.51"). */
    private val VAT_ROW = Regex("^(\\d)\\s+(\\d{1,2}[.,]\\d{1,2})\\s*%?\\s+$PRICE")
    /** Aldi: "A 02.6% Netto ...". */
    private val ALDI_VAT_ROW = Regex("^(\\p{Lu})\\s+0?(\\d{1,2}[.,]\\d)\\s*%")

    /** Lines that end the item list. */
    private val END = Regex(
        "^(total|totale|somme|summe|zwischensumme|sous-total|subtotale|arrondi|rundung|arrotondamento|vous economisez|sie sparen|risparmiate|aldi preis|prix aldi|prezzo aldi|-{5,})(\\b|chf).*",
    )

    /**
     * Rebuilds printed lines from text recognised in a photo: recognition often splits a receipt line into
     * the name and the price, so pieces whose vertical centres are within half a line height are joined,
     * left to right.
     */
    fun rows(pieces: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (piece in pieces.sortedBy { it.top + it.bottom }) {
            val center = (piece.top + piece.bottom) / 2.0
            val height = (piece.bottom - piece.top).coerceAtLeast(1)
            val row = rows.lastOrNull()?.takeIf { row ->
                val rowCenter = row.sumOf { (it.top + it.bottom) / 2.0 } / row.size
                kotlin.math.abs(center - rowCenter) < height / 2.0
            }
            if (row != null) row += piece else rows += mutableListOf(piece)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    fun parse(lines: List<String>): Receipt {
        val clean = lines.map { it.replace(' ', ' ').trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        val text = FoodSearch.normalize(clean.joinToString(" "))
        val store = when {
            "aldi suisse" in text || "aldi" in text.split(' ') -> Store.ALDI
            "migros" in text -> Store.MIGROS
            "supercard" in text || "coop" in text.split(' ') -> Store.COOP
            else -> null
        }
        val items = when (store) {
            Store.MIGROS -> migros(clean)
            Store.COOP -> coop(clean)
            Store.ALDI -> aldi(clean)
            else -> null
        }
        return Receipt(store, date(clean), items?.takeIf { it.isNotEmpty() } ?: fallback(clean))
    }

    /** The purchase date: the first date followed by a time. */
    fun date(lines: List<String>): LocalDate? = lines.firstNotNullOfOrNull { line ->
        DATE_TIME.find(line)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }
    }

    private fun isEnd(line: String) = END.matches(FoodSearch.normalize(line).ifEmpty { line })

    private fun vatRates(lines: List<String>, row: Regex): Map<String, Double> =
        lines.mapNotNull { line -> row.find(line)?.let { it.groupValues[1] to it.groupValues[2].replace(',', '.').toDouble() } }
            .toMap()

    private fun food(code: String, rates: Map<String, Double>, defaultFood: Set<String>): Boolean =
        rates[code]?.let { it < REDUCED_VAT_MAX } ?: (code in defaultFood)

    private fun price(text: String?) = text?.replace(',', '.')?.toDoubleOrNull()

    /** Items come before the first total or ruler line that follows them. */
    private fun itemSection(lines: List<String>, isItem: (String) -> Boolean): List<String> {
        val start = lines.indexOfFirst(isItem).takeIf { it >= 0 } ?: return emptyList()
        val end = (start until lines.size).firstOrNull { isEnd(lines[it]) } ?: lines.size
        return lines.subList(start, end)
    }

    private fun migros(lines: List<String>): List<ReceiptLine> {
        val rates = vatRates(lines, VAT_ROW)
        return itemSection(lines) { MIGROS_ITEM.matches(it) }.mapNotNull { line ->
            val m = MIGROS_ITEM.find(line) ?: return@mapNotNull null
            val quantity = m.groupValues[2]
            ReceiptLine(
                name = m.groupValues[1],
                quantity = quantity.toDouble(),
                weighed = '.' in quantity,
                total = price(m.groupValues[5]),
                food = food(m.groupValues[6], rates, defaultFood = setOf("1")),
            )
        }
    }

    private fun coop(lines: List<String>): List<ReceiptLine> {
        val rates = vatRates(lines, VAT_ROW)
        val items = mutableListOf<ReceiptLine>()
        for (line in itemSection(lines) { COOP_ITEM.matches(it) }) {
            val m = COOP_ITEM.find(line)
            if (m != null) {
                val quantity = m.groupValues[2].toDouble()
                // Counts print as "1.0"; a weight has grams in it ("0.268").
                val weighed = quantity != Math.floor(quantity)
                items += ReceiptLine(
                    name = m.groupValues[1],
                    quantity = quantity,
                    weighed = weighed,
                    total = price(m.groupValues[5]),
                    food = food(m.groupValues[6], rates, defaultFood = setOf("0")),
                )
                continue
            }
            // "Bon Yakult -2.10": a discount on the line above.
            val discount = DISCOUNT.find(line) ?: continue
            val last = items.removeLastOrNull() ?: continue
            items += last.copy(total = last.total?.let { it + (price(discount.groupValues[2]) ?: 0.0) })
        }
        return items
    }

    private fun aldi(lines: List<String>): List<ReceiptLine> {
        val rates = vatRates(lines, ALDI_VAT_ROW)
        val items = mutableListOf<ReceiptLine>()
        for (line in itemSection(lines) { ALDI_ITEM.matches(it) }) {
            // "0.256 kg x 44.90 CHF/kg" or "2 x 1.39" under an item gives its weight or count.
            val weight = ALDI_WEIGHT.find(line)
            val count = ALDI_COUNT.find(line)
            if (weight != null || count != null) {
                val last = items.removeLastOrNull() ?: continue
                items += if (weight != null) {
                    last.copy(quantity = weight.groupValues[1].replace(',', '.').toDouble(), weighed = true)
                } else {
                    last.copy(quantity = count!!.groupValues[1].toDouble())
                }
                continue
            }
            val m = ALDI_ITEM.find(line) ?: continue
            val code = m.groupValues[4]
            items += ReceiptLine(
                name = m.groupValues[2],
                total = price(m.groupValues[3].ifEmpty { null }),
                food = if (code.isEmpty()) null else food(code, rates, defaultFood = setOf("A")),
                articleNumber = m.groupValues[1],
            )
        }
        return items
    }

    /** Unknown layout: any line with words and a price, up to the total. Shown as food, since nothing tells. */
    private fun fallback(lines: List<String>): List<ReceiptLine> {
        val skip = Regex("\\b(chf|eur|mwst|tva|iva|vat|visa|mastercard|twint|bar|cash|rabatt|change|ruckgeld|rendu)\\b")
        return itemSection(lines) { FALLBACK_ITEM.matches(it) && !skip.containsMatchIn(FoodSearch.normalize(it)) }
            .filterNot { skip.containsMatchIn(FoodSearch.normalize(it)) }
            .mapNotNull { line ->
                val m = FALLBACK_ITEM.find(line) ?: return@mapNotNull null
                val total = price(m.groupValues[3]) ?: return@mapNotNull null
                if (total <= 0) return@mapNotNull null
                ReceiptLine(name = m.groupValues[2], quantity = m.groupValues[1].toDoubleOrNull() ?: 1.0, total = total)
            }
    }
}
