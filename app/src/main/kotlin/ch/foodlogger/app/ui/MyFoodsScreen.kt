package ch.foodlogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import ch.foodlogger.core.Product

/** The user's own food database: foods entered by hand or read from a label, which can be edited or deleted. */
@Composable
fun MyFoodsScreen(
    foods: List<Product>,
    onSelect: (Product) -> Unit,
    onEdit: (Product) -> Unit,
    onDelete: (Product) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Product?>(null) }
    pendingDelete?.let { product ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete food?") },
            text = { Text("\"${product.name}\" will be removed from My foods and the food history. Entries already logged stay.") },
            confirmButton = { TextButton(onClick = { onDelete(product); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (foods.isEmpty()) {
            item {
                Text(
                    "Foods you enter by hand or read from a label are kept here, so you only type them once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(foods, key = { it.barcode }) { product ->
            Column {
                ProductRow(
                    product = product,
                    onClick = { onSelect(product) },
                    trailing = {
                        Column {
                            TextButton(onClick = { onEdit(product) }) { Text("Edit") }
                            TextButton(onClick = { pendingDelete = product }) { Text("Delete") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
