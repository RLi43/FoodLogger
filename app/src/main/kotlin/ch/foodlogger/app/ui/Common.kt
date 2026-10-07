package ch.foodlogger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import ch.foodlogger.core.MealSlot

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Parses user input that may use a decimal comma; returns null for blank or invalid text. */
fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

/** Formats a value for an input field, without a trailing ".0". */
fun formatNumber(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
}

fun MealSlot.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
