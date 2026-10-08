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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.formatGrams
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Nutrients
import ch.foodlogger.core.Product

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortionScreen(
    product: Product,
    defaultMeal: MealSlot,
    initialGrams: Double? = null,
    canLog: Boolean,
    onLog: (Product, Double, MealSlot) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var gramsText by rememberSaveable(product.barcode) { mutableStateOf(formatNumber(initialGrams ?: product.servingGrams ?: 100.0)) }
    var meal by rememberSaveable(product.barcode) { mutableStateOf(defaultMeal) }
    val grams = parseNumber(gramsText)?.takeIf { it > 0 }

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
            listOf(50.0, 100.0, 200.0).forEach { amount ->
                AssistChip(onClick = { gramsText = formatNumber(amount) }, label = { Text("${formatGrams(amount)} g") })
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
            onClick = { grams?.let { onLog(product, it, meal) } },
            enabled = canLog && grams != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Log to Health Connect") }
        if (!canLog) {
            Text(
                "Health Connect access is missing; go back to allow it.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onEdit) { Text("Edit nutrition values") }
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
