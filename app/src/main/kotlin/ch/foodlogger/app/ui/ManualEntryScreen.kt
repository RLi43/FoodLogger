package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.MainViewModel
import ch.foodlogger.core.LabelScan
import ch.foodlogger.core.Nutrients
import ch.foodlogger.core.Product

/**
 * Per-100 g nutrition form for products that are missing or incomplete on Open Food Facts, and for
 * [generic] foods (fruit, bakery, home cooking), which have no brand or label and so hide those fields.
 */
@Composable
fun ManualEntryScreen(
    draft: Product,
    hint: String?,
    scan: LabelScan?,
    scanId: Int,
    scanning: Boolean,
    generic: Boolean,
    onPhotographLabel: () -> Unit,
    onPickLabel: () -> Unit,
    onContinue: (Product) -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = draft.barcode
    val name = rememberSaveable(key) { mutableStateOf(draft.name) }
    val brand = rememberSaveable(key) { mutableStateOf(draft.brand.orEmpty()) }
    val serving = rememberSaveable(key) { mutableStateOf(formatNumber(draft.servingGrams)) }
    val pack = rememberSaveable(key) { mutableStateOf(formatNumber(draft.packageGrams)) }
    val n = draft.per100g
    val kcal = rememberSaveable(key) { mutableStateOf(formatNumber(n.kcal)) }
    val fat = rememberSaveable(key) { mutableStateOf(formatNumber(n.fat)) }
    val saturated = rememberSaveable(key) { mutableStateOf(formatNumber(n.saturatedFat)) }
    val carbs = rememberSaveable(key) { mutableStateOf(formatNumber(n.carbs)) }
    val sugar = rememberSaveable(key) { mutableStateOf(formatNumber(n.sugar)) }
    val fiber = rememberSaveable(key) { mutableStateOf(formatNumber(n.fiber)) }
    val protein = rememberSaveable(key) { mutableStateOf(formatNumber(n.protein)) }
    val salt = rememberSaveable(key) { mutableStateOf(formatNumber(n.salt)) }

    // Values read from a label photo replace what is in the form; values the label did not show are kept.
    // The applied id is saved so a rotation does not apply the same scan over later edits.
    val appliedScan = rememberSaveable(key) { mutableStateOf(0) }
    LaunchedEffect(scanId) {
        if (scan == null || scanId == appliedScan.value) return@LaunchedEffect
        appliedScan.value = scanId
        fun MutableState<String>.fill(value: Double?) { if (value != null) this.value = formatNumber(value) }
        val p = scan.per100g
        serving.fill(scan.servingGrams)
        kcal.fill(p.kcal?.let { Math.round(it).toDouble() })
        fat.fill(p.fat)
        saturated.fill(p.saturatedFat)
        carbs.fill(p.carbs)
        sugar.fill(p.sugar)
        fiber.fill(p.fiber)
        protein.fill(p.protein)
        salt.fill(p.salt)
    }

    val valid = name.value.isNotBlank() && parseNumber(kcal.value) != null

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!draft.barcode.startsWith("manual-")) {
            Text("Barcode ${draft.barcode}", style = MaterialTheme.typography.bodySmall)
        }
        TextInput(name, "Name *")
        if (!generic) TextInput(brand, "Brand")
        NumberInput(serving, if (generic) "Typical portion (g), optional" else "Serving size (g), optional")
        if (!generic) NumberInput(pack, "Pack size (g), optional")
        if (generic) {
            Text("Per 100 g", style = MaterialTheme.typography.titleSmall)
        } else {
            Text("Per 100 g, as printed on the label", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPhotographLabel, enabled = !scanning) { Text("Scan nutrition label") }
                TextButton(onClick = onPickLabel, enabled = !scanning) { Text("From gallery") }
                if (scanning) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        NumberInput(kcal, "Energy (kcal) *")
        NumberInput(fat, "Fat (g)")
        NumberInput(saturated, "of which saturated (g)")
        NumberInput(carbs, "Carbohydrates (g)")
        NumberInput(sugar, "of which sugars (g)")
        NumberInput(fiber, "Fibre (g)")
        NumberInput(protein, "Protein (g)")
        NumberInput(salt, "Salt (g)")
        Text(
            "Label only shows kJ? Divide by 4.184 to get kcal.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = valid,
            onClick = {
                onContinue(
                    draft.copy(
                        name = name.value.trim(),
                        brand = brand.value.trim().ifEmpty { null },
                        servingGrams = parseNumber(serving.value)?.takeIf { it > 0 },
                        packageGrams = if (generic) draft.packageGrams else parseNumber(pack.value)?.takeIf { it > 0 },
                        per100g = Nutrients(
                            kcal = parseNumber(kcal.value),
                            fat = parseNumber(fat.value),
                            saturatedFat = parseNumber(saturated.value),
                            carbs = parseNumber(carbs.value),
                            sugar = parseNumber(sugar.value),
                            fiber = parseNumber(fiber.value),
                            protein = parseNumber(protein.value),
                            salt = parseNumber(salt.value),
                        ),
                        source = MainViewModel.MANUAL_SOURCE,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Continue") }
    }
}

@Composable
private fun TextInput(state: MutableState<String>, label: String) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberInput(state: MutableState<String>, label: String) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        label = { Text(label) },
        singleLine = true,
        isError = state.value.isNotBlank() && parseNumber(state.value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
