package ch.foodlogger.app

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.foodlogger.core.AppRelease
import ch.foodlogger.core.Barcodes
import ch.foodlogger.core.LabelScan
import ch.foodlogger.core.Journal
import ch.foodlogger.core.LoggedEntry
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.Product
import ch.foodlogger.core.RecentProducts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Loading(val barcode: String) : Screen
    data class Portion(val product: Product) : Screen

    /**
     * Manual entry of per-100 g values, optionally pre-filled from [draft].
     * [scan] holds values read from a label photo; [scanId] changes with every new scan so the form applies it once.
     */
    data class Manual(
        val draft: Product,
        val hint: String? = null,
        val scan: LabelScan? = null,
        val scanId: Int = 0,
        val scanning: Boolean = false,
    ) : Screen
}

/** A snackbar message; [undoRecordId] adds an "Undo" action that deletes that record. */
data class Message(val text: String, val undoRecordId: String? = null)

data class UiState(
    val screen: Screen = Screen.Home,
    val recent: List<Product> = emptyList(),
    /** Entries this app logged today, newest first. */
    val today: List<LoggedEntry> = emptyList(),
    val health: HealthStatus = HealthStatus.Checking,
    val message: Message? = null,
    /** A newer build published on GitHub, if any. */
    val update: AppRelease? = null,
    val updating: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val sink = HealthConnectSink(application)
    private val repository = ProductRepository(USER_AGENT)
    private val recentStore = TextFileStore(application, "recent.json")
    private val journalStore = TextFileStore(application, "journal.json")
    private var journal: List<LoggedEntry> = emptyList()
    private val updater = AppUpdater(application)
    val labelReader = LabelReader(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val recent = RecentProducts.decode(recentStore.read())
            journal = Journal.decode(journalStore.read())
            _state.update { it.copy(recent = recent) }
            refresh()
        }
        viewModelScope.launch {
            val release = updater.newerRelease()
            _state.update { it.copy(update = release) }
        }
    }

    fun installUpdate() {
        val release = _state.value.update ?: return
        _state.update { it.copy(updating = true) }
        viewModelScope.launch {
            try {
                updater.install(release)
            } catch (e: Exception) {
                show("Update failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(updating = false) }
            }
        }
    }

    /** Re-checks Health Connect access and today's entries; called on start and whenever the app resumes. */
    fun refresh() {
        viewModelScope.launch {
            val status = runCatching { sink.status() }.getOrDefault(HealthStatus.Unavailable)
            _state.update { it.copy(health = status) }
            if (status == HealthStatus.Ready) syncTodayWithHealthConnect() else showToday()
        }
    }

    /** Drops entries that were deleted elsewhere (e.g. in Google Health), when Health Connect lets us check. */
    private suspend fun syncTodayWithHealthConnect() {
        val (start, end) = todayRange()
        val existing = runCatching { sink.ownRecordIds(start.minusSeconds(60), end) }.getOrNull()
        val logged = Journal.between(journal, start.toEpochMilli(), end.toEpochMilli())
        // Only trust a read that sees at least one of our entries: without read access some versions
        // may return an empty list instead of failing, which must not wipe the local journal.
        if (existing != null && logged.any { it.recordId in existing }) {
            val gone = logged.filter { it.recordId !in existing }
            if (gone.isNotEmpty()) saveJournal(gone.fold(journal) { list, e -> Journal.remove(list, e.recordId) })
        }
        showToday()
    }

    private fun showToday() {
        val (start, end) = todayRange()
        _state.update { it.copy(today = Journal.between(journal, start.toEpochMilli(), end.toEpochMilli())) }
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant() to today.plusDays(1).atStartOfDay(zone).toInstant()
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

    /** Reads the nutrition label photo at [uri] and hands the values to the manual form that is open. */
    fun scanLabel(uri: Uri) {
        val screen = _state.value.screen as? Screen.Manual ?: return
        navigate(screen.copy(scanning = true))
        viewModelScope.launch {
            val scan = try {
                labelReader.read(uri)
            } catch (e: Exception) {
                // IOException for an unreadable image, MlKitException while the model is still downloading.
                updateManual(screen) { it.copy(scanning = false) }
                show("Could not read the photo: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            if (scan.valueCount == 0 && scan.servingGrams == null) {
                updateManual(screen) { it.copy(scanning = false) }
                show("No nutrition values found. Try a sharp photo of just the table, taken straight on.")
                return@launch
            }
            updateManual(screen) { it.copy(scanning = false, scan = scan, scanId = it.scanId + 1) }
            show("Filled ${scan.valueCount} values from the label. Please check them.")
        }
    }

    /** Applies [change] if the manual form for [screen]'s product is still open. */
    private fun updateManual(screen: Screen.Manual, change: (Screen.Manual) -> Screen.Manual) = _state.update { state ->
        val current = state.screen as? Screen.Manual
        if (current != null && current.draft.barcode == screen.draft.barcode) state.copy(screen = change(current)) else state
    }

    fun selectRecent(product: Product) = navigate(Screen.Portion(product))

    fun removeRecent(product: Product) = updateRecent(_state.value.recent.filterNot { it.barcode == product.barcode })

    fun goHome() = navigate(Screen.Home)

    fun log(product: Product, grams: Double, meal: MealSlot) {
        viewModelScope.launch {
            val time = Instant.now()
            val recordId = try {
                sink.log(FoodEntry(product, grams, meal, time))
            } catch (e: Exception) {
                // SecurityException when permission was revoked, IllegalArgumentException on invalid values.
                show("Could not log: ${e.message ?: e.javaClass.simpleName}")
                refresh()
                return@launch
            }
            val entry = LoggedEntry(recordId, product.name, grams, meal, product.per100g.forPortion(grams), time.toEpochMilli())
            saveJournal(Journal.add(journal, entry))
            showToday()
            updateRecent(RecentProducts.push(_state.value.recent, product))
            _state.update { it.copy(screen = Screen.Home) }
            show("Logged ${formatGrams(grams)} g of ${product.name}", undoRecordId = recordId)
        }
    }

    /** Deletes a logged entry from Health Connect and the local journal. */
    fun delete(recordId: String) {
        viewModelScope.launch {
            try {
                sink.delete(recordId)
            } catch (e: Exception) {
                show("Could not delete: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            saveJournal(Journal.remove(journal, recordId))
            showToday()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun saveJournal(list: List<LoggedEntry>) {
        journal = list
        journalStore.write(Journal.encode(list))
    }

    fun defaultMeal(): MealSlot = MealSlot.forHour(LocalTime.now().hour)

    private fun updateRecent(list: List<Product>) {
        _state.update { it.copy(recent = list) }
        viewModelScope.launch { recentStore.write(RecentProducts.encode(list)) }
    }

    private fun navigate(screen: Screen) = _state.update { it.copy(screen = screen) }

    private fun show(text: String, undoRecordId: String? = null) =
        _state.update { it.copy(message = Message(text, undoRecordId)) }

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
