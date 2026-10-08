package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.formatGrams
import ch.foodlogger.core.LoggedEntry
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Nutrients
import ch.foodlogger.core.Pantry
import ch.foodlogger.core.PantryItem
import ch.foodlogger.core.Product

/**
 * Asks how much of [product] was eaten. Taken from a [pantryItem], it shows what is left of that pack;
 * otherwise the rest of the pack can be kept in the pantry ("keep the rest"), with the amount left
 * asked for when the pack size is unknown. [onLog] gets the grams left to keep, or null.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortionScreen(
    product: Product,
    pantryItem: PantryItem?,
    defaultMeal: MealSlot,
    editing: LoggedEntry? = null,
    canLog: Boolean,
    onLog: (Product, Double, MealSlot, Double?) -> Unit,
    onEdit: () -> Unit,
    onNewPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = product.barcode + (pantryItem?.id ?: "") + (editing?.recordId ?: "")
    val initialGrams = editing?.grams ?: pantryItem?.oneServingGrams ?: product.servingGrams ?: 100.0
    var gramsText by rememberSaveable(key) { mutableStateOf(formatNumber(initialGrams)) }
    var meal by rememberSaveable(key) { mutableStateOf(editing?.meal ?: defaultMeal) }
    var keep by rememberSaveable(key) { mutableStateOf(pantryItem == null && editing == null && Pantry.keepByDefault(product, initialGrams)) }
    var leftText by rememberSaveable(key) { mutableStateOf("") }
    val grams = parseNumber(gramsText)?.takeIf { it > 0 }
    val pack = product.packageGrams
    // Grams to keep: what the pack size leaves after this portion, or what the user says is left.
    val keepGramsLeft = when {
        pantryItem != null || !keep -> null
        pack != null -> grams?.let { pack - it }?.takeIf { it >= Pantry.FINISHED_BELOW_GRAMS }
        else -> parseNumber(leftText)?.takeIf { it > 0 }
    }
    val keepIncomplete = pantryItem == null && keep && keepGramsLeft == null

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            listOfNotNull(product.brand, product.source, product.barcode.takeUnless { it.startsWith("manual-") })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        pantryItem?.let { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "From your pantry: ${amountLeft(item)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNewPack) { Text("New pack") }
            }
        }

        OutlinedTextField(
            value = gramsText,
            onValueChange = { gramsText = it },
            label = { Text("Amount (g or ml)") },
            singleLine = true,
            isError = grams == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            product.servingGrams?.let { serving ->
                AssistChip(onClick = { gramsText = formatNumber(serving) }, label = { Text("1 serving (${formatGrams(serving)} g)") })
            }
            when {
                pantryItem != null -> AssistChip(
                    onClick = { gramsText = formatNumber(pantryItem.gramsLeft) },
                    label = { Text("All that's left (${formatGrams(pantryItem.gramsLeft)} g)") },
                )
                pack != null -> AssistChip(
                    onClick = { gramsText = formatNumber(pack) },
                    label = { Text("Whole pack (${packLabel(product, pack)})") },
                )
            }
            listOf(50.0, 100.0, 200.0).forEach { amount ->
                AssistChip(onClick = { gramsText = formatNumber(amount) }, label = { Text("${formatGrams(amount)} g") })
            }
        }

        // Changing a logged entry leaves the pantry alone.
        if (pantryItem == null && editing == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the rest in the pantry")
                    val left = if (pack != null) grams?.let { pack - it } else null
                    Text(
                        when {
                            pack == null -> "Eat more of it later with one tap"
                            left == null -> ""
                            left < Pantry.FINISHED_BELOW_GRAMS -> "Nothing left after this portion"
                            else -> "${leftLabel(product, left)} left after this"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = keep, onCheckedChange = { keep = it })
            }
            if (keep && pack == null) {
                OutlinedTextField(
                    value = leftText,
                    onValueChange = { leftText = it },
                    label = { Text("Left in the pack (g or ml)") },
                    singleLine = true,
                    isError = keepGramsLeft == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text("Meal", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MealSlot.entries.forEach { slot ->
                FilterChip(
                    selected = meal == slot,
                    onClick = { meal = slot },
                    label = { Text(slot.label()) },
                )
            }
        }

        NutrientTable(
            title = grams?.let { "In ${formatGrams(it)} g" } ?: "Per 100 g",
            nutrients = grams?.let(product.per100g::forPortion) ?: product.per100g,
        )

        Button(
            onClick = { grams?.let { onLog(product, it, meal, keepGramsLeft) } },
            // With "keep the rest" on, logging waits for an amount to keep, unless the pack is used up.
            enabled = canLog && grams != null && !(keepIncomplete && pack == null),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (editing != null) "Save change" else "Log to Health Connect") }
        if (!canLog) {
            Text(
                "Health Connect access is missing; go back to allow it.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Editing the values would leave this screen and lose which entry is being changed.
        if (editing == null) TextButton(onClick = onEdit) { Text("Edit nutrition values") }
    }
}

@Composable
private fun NutrientTable(title: String, nutrients: Nutrients) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        nutrientRows(nutrients).forEach { (label, value, unit) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text(value?.let { "${formatNumber(Math.round(it * 10) / 10.0)} $unit" } ?: "–")
            }
        }
    }
}

fun nutrientRows(n: Nutrients): List<Triple<String, Double?, String>> = listOf(
    Triple("Energy", n.kcal, "kcal"),
    Triple("Fat", n.fat, "g"),
    Triple("  of which saturated", n.saturatedFat, "g"),
    Triple("Carbohydrates", n.carbs, "g"),
    Triple("  of which sugars", n.sugar, "g"),
    Triple("Fibre", n.fiber, "g"),
    Triple("Protein", n.protein, "g"),
    Triple("Salt", n.salt, "g"),
)

/** "6 of 8 servings left", "6 servings left" or "180 g left". */
fun amountLeft(item: PantryItem): String {
    val left = item.servingsLeft ?: return "${formatGrams(item.gramsLeft)} g left"
    val total = item.servingsTotal ?: return "${servings(left)} left"
    return "${formatServings(left)} of ${servings(total)} left"
}

private fun packLabel(product: Product, pack: Double): String =
    Pantry.servingsPerPack(product)?.let(::servings) ?: "${formatGrams(pack)} g"

private fun leftLabel(product: Product, grams: Double): String =
    product.servingGrams?.let { servings(grams / it) } ?: "${formatGrams(grams)} g"

/** "1 serving", "5.5 servings". */
private fun servings(count: Double): String = formatServings(count).let { if (it == "1") "1 serving" else "$it servings" }

/** Servings to the nearest half, e.g. 6, 5.5. */
fun formatServings(servings: Double): String = formatNumber(Math.round(servings * 2) / 2.0)
