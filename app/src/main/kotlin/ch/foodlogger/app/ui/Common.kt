package ch.foodlogger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Product

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

/**
 * One food in a list: name, then brand, pack size and kcal per 100 g. Products without nutrition
 * values say so, since they cannot be logged without filling them in first.
 */
@Composable
fun ProductRow(product: Product, onClick: () -> Unit, quantity: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val kcal = product.per100g.kcal
    ListItem(
        headlineContent = { Text(product.name) },
        supportingContent = {
            val details = listOfNotNull(product.brand, quantity, kcal?.let { "${it.toInt()} kcal / 100 g" }).joinToString(" · ")
            if (product.per100g.isEmpty) {
                Text(listOf(details, "no nutrition values").filter { it.isNotEmpty() }.joinToString(" · "), color = MaterialTheme.colorScheme.error)
            } else {
                Text(details)
            }
        },
        trailingContent = trailing,
        modifier = Modifier.clickableRow(onClick),
    )
}
