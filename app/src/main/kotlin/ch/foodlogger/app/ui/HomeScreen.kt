package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.BuildConfig
import ch.foodlogger.app.HealthStatus
import ch.foodlogger.app.UiState
import ch.foodlogger.core.AppRelease
import ch.foodlogger.core.LoggedEntry
import ch.foodlogger.core.Product

@Composable
fun HomeScreen(
    state: UiState,
    onScan: () -> Unit,
    onScanPhoto: () -> Unit,
    onSearch: () -> Unit,
    onReadLabel: () -> Unit,
    onGenericEntry: () -> Unit,
    onMyFoods: () -> Unit,
    onFood: (Product) -> Unit,
    onRemoveFromHistory: (Product) -> Unit,
    onToday: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onGrantPermission: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HealthBanner(state.health, onGrantPermission, onInstallHealthConnect) }
        state.update?.let { release ->
            item { UpdateBanner(release, state.updating, onInstallUpdate) }
        }
        item { TodaySummary(state.today, onToday) }
        item { SectionTitle("Packaged food") }
        item {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text("Scan barcode", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch, modifier = Modifier.weight(1f)) { Text("Search") }
                OutlinedButton(onClick = onScanPhoto, modifier = Modifier.weight(1f)) { Text("Barcode photo") }
                OutlinedButton(onClick = onReadLabel, modifier = Modifier.weight(1f)) { Text("Read label") }
            }
        }
        item { SectionTitle("Generic food") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGenericEntry, modifier = Modifier.weight(1f)) { Text("Enter by hand") }
                // Search in a list of generic foods (fruit, bakery, cheese from the counter) is planned.
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Search (coming later)") }
            }
        }
        item {
            TextButton(onClick = onMyFoods) {
                Text(if (state.myFoods.isEmpty()) "My foods" else "My foods (${state.myFoods.size})")
            }
        }
        if (state.history.isNotEmpty()) {
            item { SectionTitle("Food history") }
            items(state.history.take(HISTORY_SHOWN).map { it.product }, key = { it.barcode }) { product ->
                Column {
                    ProductRow(
                        product = product,
                        onClick = { onFood(product) },
                        trailing = { TextButton(onClick = { onRemoveFromHistory(product) }) { Text("Remove") } },
                    )
                    HorizontalDivider()
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text(
                    "FoodLogger build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCheckForUpdate, enabled = !state.checkingUpdate) {
                    Text(if (state.checkingUpdate) "Checking…" else "Check for updates")
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

/** Most-used foods shown on the home screen; the rest are still found by search. */
private const val HISTORY_SHOWN = 30

@Composable
private fun UpdateBanner(release: AppRelease, updating: Boolean, onInstall: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: build ${release.versionCode}", style = MaterialTheme.typography.titleSmall)
            if (release.notes.isNotEmpty()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onInstall, enabled = !updating) { Text(if (updating) "Downloading…" else "Install update") }
        }
    }
}

/** One line with today's totals; tapping it opens the Today page with the entries. */
@Composable
private fun TodaySummary(entries: List<LoggedEntry>, onClick: () -> Unit) {
    val totals = todayTotals(entries)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (entries.isEmpty()) "Today: nothing logged yet"
            else "Today: ${listOf(totals, if (entries.size == 1) "1 entry" else "${entries.size} entries").filter { it.isNotEmpty() }.joinToString(" · ")}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp),
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

