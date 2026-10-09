package ch.foodlogger.core

object Barcodes {
    /**
     * Returns the product code in a scanned value, or null when it holds none.
     *
     * A plain EAN-8, UPC-A (12), EAN-13 or GTIN-14 code with a valid check digit is returned as is.
     * 2D codes (GS1 Data Matrix, GS1 QR codes, GS1 Digital Link URLs) carry the product number
     * in AI 01 next to batch and date data; that GTIN is returned in the short form Open Food Facts
     * uses, e.g. "07627538413275" becomes "7627538413275".
     */
    fun normalize(raw: String): String? {
        val code = raw.trim()
        if (code.length in setOf(8, 12, 13, 14) && code.all { it in '0'..'9' }) return code.takeIf { validCheckDigit(it) }
        val gtin = gtinFromDigitalLink(code) ?: gtinFromElementString(code) ?: return null
        return shorten(gtin).takeIf { validCheckDigit(gtin) }
    }

    private fun validCheckDigit(code: String): Boolean {
        val sum = code.dropLast(1).reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return (10 - sum % 10) % 10 == code.last() - '0'
    }

    /** A GTIN-14 without the zeros that only pad it: 13 digits, or 8 for an EAN-8. */
    private fun shorten(gtin: String): String = when {
        gtin.startsWith("000000") -> gtin.takeLast(8)
        gtin.startsWith("0") -> gtin.drop(1)
        else -> gtin
    }

    private val digitalLink = Regex("""^https?://[^/]+(?:/[^?#]*)?/01/(\d{8,14})(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

    /** "https://id.gs1.org/01/07627538413275/10/ABC" and similar product URLs. */
    private fun gtinFromDigitalLink(code: String): String? =
        digitalLink.find(code)?.groupValues?.get(1)?.padStart(14, '0')

    private const val GS = '\u001D'

    /** Total length (AI and data) of the GS1 fields that never end with a separator, by the AI's first two digits. */
    private val fixedLength = mapOf(
        "00" to 20, "01" to 16, "02" to 16, "03" to 16, "04" to 18,
        "11" to 8, "12" to 8, "13" to 8, "14" to 8, "15" to 8, "16" to 8, "17" to 8, "18" to 8, "19" to 8,
        "20" to 4, "31" to 10, "32" to 10, "33" to 10, "34" to 10, "35" to 10, "36" to 10, "41" to 16,
    )

    /**
     * AI 01 from a GS1 element string, either human readable ("(01)07627538413275(10)…") or as
     * decoded ("0107627538413275102026…", fields of varying length ended by a GS character).
     */
    private fun gtinFromElementString(code: String): String? {
        Regex("""\(01\)(\d{14})""").find(code)?.let { return it.groupValues[1] }
        // Symbology identifier of GS1 Data Matrix / QR / 128, and a leading FNC1, when the reader keeps them.
        var rest = code.removePrefix("]d2").removePrefix("]Q3").removePrefix("]C1").trimStart(GS)
        while (rest.length >= 2 && rest.take(2).all { it in '0'..'9' }) {
            val length = fixedLength[rest.take(2)]
            if (rest.startsWith("01")) {
                return rest.substring(2).take(14).takeIf { it.length == 14 && it.all { c -> c in '0'..'9' } }
            }
            rest = if (length != null) rest.drop(length) else rest.substringAfter(GS, "")
            rest = rest.trimStart(GS)
        }
        return null
    }
}
