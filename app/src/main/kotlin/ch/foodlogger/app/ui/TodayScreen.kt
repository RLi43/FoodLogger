package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.formatGrams
import ch.foodlogger.core.Journal
import ch.foodlogger.core.LoggedEntry
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Entries this app logged today, with totals; tapping one edits its amount or meal. */
@Composable
fun TodayScreen(
    entries: List<LoggedEntry>,
    onEdit: (LoggedEntry) -> Unit,
    onDelete: (LoggedEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<LoggedEntry?>(null) }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete entry?") },
            text = { Text("\"${entry.name}\" (${formatGrams(entry.grams)} g) will be removed from Health Connect and Google Health.") },
            confirmButton = {
                TextButton(onClick = { onDelete(entry); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                listOf(todayTotals(entries), "logged with FoodLogger").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entries.isEmpty()) {
            item { Text("Nothing logged yet today.") }
        }
        items(entries, key = { it.recordId }) { entry ->
            Column {
                ListItem(
                    overlineContent = { Text("${timeFormat.format(Date(entry.loggedAtMillis))} · ${entry.meal.label()}") },
                    headlineContent = { Text(entry.name) },
                    supportingContent = {
                        Text(listOfNotNull("${formatGrams(entry.grams)} g", entry.nutrients.kcal?.let { "${it.roundToInt()} kcal" }).joinToString(" · "))
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { onEdit(entry) }) { Text("Edit") }
                            TextButton(onClick = { pendingDelete = entry }) { Text("Delete") }
                        }
                    },
                    modifier = Modifier.clickableRow { onEdit(entry) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Today's kcal and macros, e.g. "1450 kcal · P 80 g · C 150 g · F 50 g"; empty when nothing is known. */
fun todayTotals(entries: List<LoggedEntry>): String {
    val total = Journal.total(entries)
    return listOfNotNull(
        total.kcal?.let { "${it.roundToInt()} kcal" },
        total.protein?.let { "P ${it.roundToInt()} g" },
        total.carbs?.let { "C ${it.roundToInt()} g" },
        total.fat?.let { "F ${it.roundToInt()} g" },
    ).joinToString(" · ")
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
