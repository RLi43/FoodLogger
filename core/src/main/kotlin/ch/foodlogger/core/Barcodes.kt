package ch.foodlogger.core

object Barcodes {
    /**
     * Trims whitespace and returns the barcode if it is an EAN-8, UPC-A (12),
     * EAN-13 or GTIN-14 code with a valid check digit; otherwise null.
     */
    fun normalize(raw: String): String? {
        val code = raw.trim()
        if (code.length !in setOf(8, 12, 13, 14) || !code.all { it in '0'..'9' }) return null
        val sum = code.dropLast(1).reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return code.takeIf { (10 - sum % 10) % 10 == it.last() - '0' }
    }
}
