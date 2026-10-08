package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.Screen
import ch.foodlogger.core.FoodSearch
import ch.foodlogger.core.Product
import ch.foodlogger.core.Store

/**
 * Search for packaged food: the user's own foods are matched while typing, Open Food Facts only
 * when the user presses Search. Picking a store first is optional but keeps the list short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    screen: Screen.Search,
    ownFoods: List<Product>,
    onQueryChange: (String) -> Unit,
    onStoreChange: (Store?) -> Unit,
    onSearch: () -> Unit,
    onSelect: (Product) -> Unit,
    onReadLabel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val local = remember(ownFoods, screen.query, screen.store) { FoodSearch.local(ownFoods, screen.query, screen.store) }
    val remote = remember(screen.hits, screen.store) { screen.hits?.let { FoodSearch.remote(it, screen.store) } }
    val submit = {
        keyboard?.hide()
        onSearch()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = screen.store == null, onClick = { onStoreChange(null) }, label = { Text("Any store") })
                Store.entries.forEach { store ->
                    FilterChip(selected = screen.store == store, onClick = { onStoreChange(store) }, label = { Text(store.label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = screen.query,
                    onValueChange = onQueryChange,
                    label = { Text("Product, e.g. Caffè Latte") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = submit, enabled = screen.query.isNotBlank() && !screen.searching) { Text("Search") }
            }
        }

        if (local.isNotEmpty()) {
            item { SectionTitle("My foods and history") }
            items(local, key = { "own-${it.barcode}" }) { product ->
                Column {
                    ProductRow(product, onClick = { onSelect(product) })
                    HorizontalDivider()
                }
            }
        }

        item { SectionTitle("Open Food Facts") }
        when {
            screen.searching -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            screen.error != null -> item { Text(screen.error, color = MaterialTheme.colorScheme.error) }
            remote == null -> item {
                Text("Press Search to look for products sold in Switzerland.", style = MaterialTheme.typography.bodySmall)
            }
            remote.isEmpty() -> item {
                val where = screen.store?.let { " at ${it.label}" }.orEmpty()
                Text("No products found for \"${screen.searchedQuery}\"$where.", style = MaterialTheme.typography.bodySmall)
            }
            else -> items(remote, key = { "off-${it.product.barcode}" }) { hit ->
                Column {
                    ProductRow(hit.product, onClick = { onSelect(hit.product) }, quantity = hit.quantity)
                    HorizontalDivider()
                }
            }
        }

        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not found?", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onReadLabel, modifier = Modifier.fillMaxWidth()) { Text("Read the nutrition label") }
            }
        }
    }
}
