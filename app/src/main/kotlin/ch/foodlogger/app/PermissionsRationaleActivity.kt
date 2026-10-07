package ch.foodlogger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.foodlogger.app.ui.FoodLoggerTheme

/** Privacy explanation that Health Connect shows from its permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoodLoggerTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("How FoodLogger uses your data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "FoodLogger only writes nutrition entries (food name, meal, energy and macronutrients) " +
                                "to Health Connect when you tap \"Log\". It does not read any health data.",
                        )
                        Text(
                            "To look up a product, the scanned barcode is sent to Open Food Facts " +
                                "(world.openfoodfacts.org). Nothing else leaves your phone, and the app has no account or server.",
                        )
                        Button(onClick = ::finish) { Text("OK") }
                    }
                }
            }
        }
    }
}
