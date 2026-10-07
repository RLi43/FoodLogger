package ch.foodlogger.app

import android.content.Context
import ch.foodlogger.core.Product
import ch.foodlogger.core.RecentProducts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Recently logged products, kept in app-private storage for one-tap re-logging. */
class RecentStore(context: Context) {

    private val file = File(context.filesDir, "recent.json")

    suspend fun load(): List<Product> = withContext(Dispatchers.IO) {
        if (file.exists()) RecentProducts.decode(file.readText()) else emptyList()
    }

    suspend fun save(list: List<Product>) = withContext(Dispatchers.IO) {
        file.writeText(RecentProducts.encode(list))
    }
}
