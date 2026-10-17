package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.MatchWay
import ch.foodlogger.app.ReceiptDraft
import ch.foodlogger.app.ReceiptRow
import ch.foodlogger.app.formatGrams
import ch.foodlogger.core.Product
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The checklist of a receipt: food lines start ticked, each with its matched product, guesses to pick from,
 * or ways to find it. Ticked lines go into the pantry as unopened packs; lines without a product go in under
 * their receipt name and are matched when first eaten.
 */
@Composable
fun ReceiptScreen(
    draft: ReceiptDraft,
    onToggle: (ReceiptRow) -> Unit,
    onGrams: (ReceiptRow, String) -> Unit,
    onPick: (ReceiptRow, Product) -> Unit,
    onReject: (ReceiptRow) -> Unit,
    onMatch: (ReceiptRow, MatchWay) -> Unit,
    onAdd: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNonFood by rememberSaveable { mutableStateOf(false) }
    val (food, nonFood) = draft.rows.partition { it.line.food != false }
    val ticked = draft.rows.count { it.ticked }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val date = draft.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(draft.store?.label ?: "Receipt", date).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Tick what goes into the pantry. Items without a product go in under their receipt name; " +
                        "you pick the product the first time you eat from them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(food, key = { "row-${it.id}" }) { row ->
            ReceiptRowCard(row, onToggle, onGrams, onPick, onReject, onMatch)
        }
        if (nonFood.isNotEmpty()) {
            item {
                TextButton(onClick = { showNonFood = !showNonFood }) {
                    Text(if (showNonFood) "Hide items that are not food" else "Not food (${nonFood.size})")
                }
            }
            if (showNonFood) items(nonFood, key = { "row-${it.id}" }) { row ->
                ReceiptRowCard(row, onToggle, onGrams, onPick, onReject, onMatch)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onAdd, enabled = ticked > 0, modifier = Modifier.weight(1f)) {
                    Text(if (ticked == 1) "Add 1 item to pantry" else "Add $ticked items to pantry")
                }
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiptRowCard(
    row: ReceiptRow,
    onToggle: (ReceiptRow) -> Unit,
    onGrams: (ReceiptRow, String) -> Unit,
    onPick: (ReceiptRow, Product) -> Unit,
    onReject: (ReceiptRow) -> Unit,
    onMatch: (ReceiptRow, MatchWay) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.ticked, onCheckedChange = { onToggle(row) })
                Column(Modifier.weight(1f)) {
                    Text(row.line.name, fontWeight = FontWeight.SemiBold)
                    Text(lineDetails(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.ticked) {
                    OutlinedTextField(
                        value = row.grams,
                        onValueChange = { onGrams(row, it) },
                        label = { Text("Grams") },
                        singleLine = true,
                        isError = row.gramsValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
            if (row.ticked) Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val product = row.product
                when {
                    product != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(productLabel(product), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onReject(row) }) { Text("Change") }
                    }
                    row.guesses.isNotEmpty() && !row.rejected -> {
                        Text("Is it one of these?", style = MaterialTheme.typography.labelMedium)
                        row.guesses.forEach { guess ->
                            OutlinedButton(onClick = { onPick(row, guess) }, modifier = Modifier.fillMaxWidth()) {
                                Text(productLabel(guess), modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (row.queued) Text("Still searching Open Food Facts…", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onReject(row) }) { Text("None of these") }
                    }
                    row.queued && !row.rejected ->
                        Text("Searching Open Food Facts…", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("No product yet. Find it, or add it as it is and pick it later.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onMatch(row, MatchWay.SEARCH) }) { Text("Search") }
                            OutlinedButton(onClick = { onMatch(row, MatchWay.SCAN) }) { Text("Scan barcode") }
                            OutlinedButton(onClick = { onMatch(row, MatchWay.LABEL) }) { Text("Read label") }
                            OutlinedButton(onClick = { onMatch(row, MatchWay.GENERIC) }) { Text("Generic food") }
                            OutlinedButton(onClick = { onMatch(row, MatchWay.BY_HAND) }) { Text("Enter by hand") }
                        }
                    }
                }
            }
        }
    }
}

/** "2 packs · CHF 1.94", "0.268 kg · CHF 7.10". */
private fun lineDetails(row: ReceiptRow): String {
    val line = row.line
    val amount = when {
        line.weighed -> "${formatGrams(line.quantity * 1000)} g"
        line.packs == 1 -> "1 pack"
        else -> "${line.packs} packs"
    }
    return listOfNotNull(amount, line.total?.let { "CHF %.2f".format(java.util.Locale.ROOT, it) }).joinToString(" · ")
}

private fun productLabel(product: Product): String =
    listOfNotNull(product.name, product.brand?.takeIf { it.isNotBlank() }).joinToString(" · ")
