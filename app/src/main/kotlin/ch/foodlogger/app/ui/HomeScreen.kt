package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.HealthStatus
import ch.foodlogger.app.formatGrams
import ch.foodlogger.app.UiState
import ch.foodlogger.core.Journal
import ch.foodlogger.core.LoggedEntry
import ch.foodlogger.core.Product
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    state: UiState,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onRecent: (Product) -> Unit,
    onRemoveRecent: (Product) -> Unit,
    onDeleteEntry: (LoggedEntry) -> Unit,
    onGrantPermission: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<LoggedEntry?>(null) }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete entry?") },
            text = { Text("\"${entry.name}\" (${formatGrams(entry.grams)} g) will be removed from Health Connect and Google Health.") },
            confirmButton = {
                TextButton(onClick = { onDeleteEntry(entry); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HealthBanner(state.health, onGrantPermission, onInstallHealthConnect) }
        item {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text("Scan barcode", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                Text("Enter manually")
            }
        }
        if (state.today.isNotEmpty()) {
            item { TodayHeader(state.today) }
            items(state.today, key = { it.recordId }) { entry ->
                Column {
                    ListItem(
                        overlineContent = { Text("${timeFormat.format(Date(entry.loggedAtMillis))} · ${entry.meal.label()}") },
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(listOfNotNull("${formatGrams(entry.grams)} g", entry.nutrients.kcal?.let { "${it.roundToInt()} kcal" }).joinToString(" · "))
                        },
                        trailingContent = { TextButton(onClick = { pendingDelete = entry }) { Text("Delete") } },
                    )
                    HorizontalDivider()
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(state.recent, key = { it.barcode }) { product ->
                Column {
                    ListItem(
                        headlineContent = { Text(product.name) },
                        supportingContent = {
                            Text(listOfNotNull(product.brand, product.per100g.kcal?.let { "${it.toInt()} kcal / 100 g" }).joinToString(" · "))
                        },
                        trailingContent = { TextButton(onClick = { onRemoveRecent(product) }) { Text("Remove") } },
                        modifier = Modifier.clickableRow { onRecent(product) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TodayHeader(entries: List<LoggedEntry>) {
    val total = Journal.total(entries)
    Column(Modifier.padding(top = 12.dp)) {
        Text("Today", style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                total.kcal?.let { "${it.roundToInt()} kcal" },
                total.protein?.let { "P ${it.roundToInt()} g" },
                total.carbs?.let { "C ${it.roundToInt()} g" },
                total.fat?.let { "F ${it.roundToInt()} g" },
            ).plus("logged with FoodLogger").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HealthBanner(status: HealthStatus, onGrant: () -> Unit, onInstall: () -> Unit) {
    val (text, action) = when (status) {
        HealthStatus.Ready, HealthStatus.Checking -> return
        HealthStatus.NeedsPermission -> "Allow FoodLogger to write nutrition data to Health Connect." to ("Allow" to onGrant)
        HealthStatus.NeedsInstall -> "Health Connect needs to be installed or updated." to ("Open Play Store" to onInstall)
        HealthStatus.Unavailable -> "Health Connect is not available on this device." to null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
        }
    }
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
