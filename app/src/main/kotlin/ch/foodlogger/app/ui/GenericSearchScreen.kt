package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.foodlogger.core.GenericFood
import ch.foodlogger.core.GenericFoods

/**
 * Search for generic food (fruit, bread, cheese, dishes) in the bundled Swiss Food Composition Database.
 * The list is on the phone, so results update while typing; foods logged before come first.
 */
@Composable
fun GenericSearchScreen(
    query: String,
    foods: List<GenericFood>,
    loggedBefore: Set<String>,
    languages: List<String>,
    onQueryChange: (String) -> Unit,
    onSelect: (GenericFood) -> Unit,
    onEnterByHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = remember(foods, query, loggedBefore) {
        GenericFoods.search(foods, query, languages).sortedBy { "${GenericFoods.ID_PREFIX}${it.id}" !in loggedBefore }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Food, e.g. apple, croissant, rice") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            foods.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            query.isNotBlank() && results.isEmpty() -> item {
                Text("No generic food found for \"${query.trim()}\".", style = MaterialTheme.typography.bodySmall)
            }
            else -> items(results, key = { it.id }) { food ->
                Column {
                    ProductRow(food.toProduct(languages), onClick = { onSelect(food) })
                    HorizontalDivider()
                }
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not found?", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onEnterByHand, modifier = Modifier.fillMaxWidth()) { Text("Enter by hand") }
            }
        }
        item {
            Text(
                GenericFoods.CREDIT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
