package ch.foodlogger.app

import android.app.Application
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.foodlogger.core.Barcodes
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Product
import ch.foodlogger.core.RecentProducts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Loading(val barcode: String) : Screen
    data class Portion(val product: Product) : Screen

    /** Manual entry of per-100 g values, optionally pre-filled from [draft]. */
    data class Manual(val draft: Product, val hint: String? = null) : Screen
}

data class UiState(
    val screen: Screen = Screen.Home,
    val recent: List<Product> = emptyList(),
    val health: HealthStatus = HealthStatus.Checking,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val sink = HealthConnectSink(application)
    private val repository = ProductRepository(USER_AGENT)
    private val recentStore = RecentStore(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(recent = recentStore.load()) } }
        refreshHealthStatus()
    }

    fun refreshHealthStatus() {
        viewModelScope.launch {
            val status = runCatching { sink.status() }.getOrDefault(HealthStatus.Unavailable)
            _state.update { it.copy(health = status) }
        }
    }

    fun onScanned(raw: String) {
        val barcode = Barcodes.normalize(raw)
        if (barcode == null) {
            show("Unsupported barcode: $raw")
            return
        }
        // Products logged before are re-used directly, which also works offline.
        _state.value.recent.firstOrNull { it.barcode == barcode }?.let {
            navigate(Screen.Portion(it))
            return
        }
        navigate(Screen.Loading(barcode))
        viewModelScope.launch {
            val next = when (val result = repository.lookup(barcode, preferredLanguages())) {
                is LookupResult.Found ->
                    if (result.product.per100g.isEmpty) {
                        Screen.Manual(result.product, "No nutrition values on Open Food Facts yet.")
                    } else {
                        Screen.Portion(result.product)
                    }
                LookupResult.NotFound ->
                    Screen.Manual(blankProduct(barcode), "Product $barcode is not on Open Food Facts.")
                is LookupResult.Failed ->
                    Screen.Manual(blankProduct(barcode), result.reason)
            }
            // Ignore the result if the user navigated away while it was loading.
            _state.update { if (it.screen == Screen.Loading(barcode)) it.copy(screen = next) else it }
        }
    }

    fun startManualEntry() = navigate(Screen.Manual(blankProduct(null)))

    fun editProduct(product: Product) = navigate(Screen.Manual(product))

    fun confirmManual(product: Product) = navigate(Screen.Portion(product))

    fun selectRecent(product: Product) = navigate(Screen.Portion(product))

    fun removeRecent(product: Product) = updateRecent(_state.value.recent.filterNot { it.barcode == product.barcode })

    fun goHome() = navigate(Screen.Home)

    fun log(product: Product, grams: Double, meal: MealSlot) {
        viewModelScope.launch {
            try {
                sink.log(FoodEntry(product, grams, meal, Instant.now()))
            } catch (e: Exception) {
                // SecurityException when permission was revoked, IllegalArgumentException on invalid values.
                show("Could not log: ${e.message ?: e.javaClass.simpleName}")
                refreshHealthStatus()
                return@launch
            }
            updateRecent(RecentProducts.push(_state.value.recent, product))
            _state.update { it.copy(screen = Screen.Home) }
            show("Logged ${formatGrams(grams)} g of ${product.name}")
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun defaultMeal(): MealSlot = MealSlot.forHour(LocalTime.now().hour)

    private fun updateRecent(list: List<Product>) {
        _state.update { it.copy(recent = list) }
        viewModelScope.launch { recentStore.save(list) }
    }

    private fun navigate(screen: Screen) = _state.update { it.copy(screen = screen) }

    private fun show(message: String) = _state.update { it.copy(message = message) }

    private fun blankProduct(barcode: String?) = Product(
        barcode = barcode ?: "manual-${UUID.randomUUID()}",
        name = "",
        source = MANUAL_SOURCE,
    )

    companion object {
        const val MANUAL_SOURCE = "Manual"
        private const val USER_AGENT = "FoodLogger-Android/0.1 (https://github.com/RLi43/FoodLogger)"

        /** Device languages first, then the Swiss national languages and English as fallbacks. */
        fun preferredLanguages(): List<String> {
            val locales = LocaleList.getDefault()
            val device = (0 until locales.size()).map { locales[it].language }
            return (device + listOf("de", "fr", "it", "en")).distinct()
        }
    }
}
