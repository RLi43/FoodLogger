package ch.foodlogger.app

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.foodlogger.core.AppRelease
import ch.foodlogger.core.Barcodes
import ch.foodlogger.core.FoodHistory
import ch.foodlogger.core.FoodSearch
import ch.foodlogger.core.GenericFood
import ch.foodlogger.core.GenericFoods
import ch.foodlogger.core.HistoryEntry
import ch.foodlogger.core.LabelScan
import ch.foodlogger.core.Journal
import ch.foodlogger.core.LoggedEntry
import ch.foodlogger.core.MealSlot
import ch.foodlogger.core.MyFoods
import ch.foodlogger.core.Pantry
import ch.foodlogger.core.PantryItem
import ch.foodlogger.core.Product
import ch.foodlogger.core.Receipt
import ch.foodlogger.core.ReceiptLine
import ch.foodlogger.core.ReceiptMatch
import ch.foodlogger.core.ReceiptMatching
import ch.foodlogger.core.Receipts
import ch.foodlogger.core.SearchHit
import ch.foodlogger.core.SearchLimit
import ch.foodlogger.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Loading(val barcode: String) : Screen
    /**
     * How much of [product] to log; [pantryId] when it is taken from a pack kept in the pantry.
     * With [editing], it changes that logged entry instead of adding one.
     */
    data class Portion(val product: Product, val pantryId: String? = null, val editing: LoggedEntry? = null) : Screen

    /**
     * Manual entry of per-100 g values, optionally pre-filled from [draft].
     * [scan] holds values read from a label photo; [scanId] changes with every new scan so the form applies it once.
     * A [generic] food (fruit, bakery, home cooking) has no brand and no label to read.
     */
    data class Manual(
        val draft: Product,
        val hint: String? = null,
        val scan: LabelScan? = null,
        val scanId: Int = 0,
        val scanning: Boolean = false,
        val generic: Boolean = false,
    ) : Screen

    /**
     * Search for packaged food in My foods, the food history and Open Food Facts.
     * [hits] are the Open Food Facts results for [searchedQuery], not yet narrowed to [store].
     */
    data class Search(
        val query: String = "",
        val store: Store? = null,
        val searchedQuery: String? = null,
        val hits: List<SearchHit>? = null,
        val searching: Boolean = false,
        val error: String? = null,
    ) : Screen

    data object MyFoods : Screen

    /** Search in the bundled list of generic foods (fruit, bread, cheese, dishes); runs while typing, offline. */
    data class GenericSearch(val query: String = "") : Screen

    /** Entries logged today, which can be edited or deleted. */
    data object Today : Screen

    /** A shared or picked receipt is being read. */
    data object ReceiptReading : Screen

    /** The checklist of the receipt in [UiState.receipt]: tick what goes into the pantry and fix matches. */
    data object Receipt : Screen
}

/**
 * One line of the receipt under review. [product] is the match (remembered, confident or chosen by the user);
 * [guesses] are candidates for the user to pick from. [grams] is the field's text: the receipt weight, pack
 * size or matched product's pack size, or what the user typed. [queued] while an Open Food Facts search is
 * still to come for it.
 */
data class ReceiptRow(
    val id: Int,
    val line: ReceiptLine,
    val key: String,
    val ticked: Boolean,
    val product: Product? = null,
    val guesses: List<Product> = emptyList(),
    val grams: String = "",
    val queued: Boolean = false,
    /** The user turned down the guesses or the match: show the ways to find the product instead. */
    val rejected: Boolean = false,
) {
    val gramsValue: Double? get() = grams.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
}

data class ReceiptDraft(val store: Store?, val date: LocalDate?, val rows: List<ReceiptRow>)

/** Where a product the user picks goes instead of the amount screen: a receipt row, or a pantry pack without a match. */
sealed interface MatchTarget {
    val name: String

    data class Row(val rowId: Int, override val name: String) : MatchTarget
    data class Pack(val pantryId: String, override val name: String) : MatchTarget
}

/** Ways to find the product for a receipt line when no guess fits. */
enum class MatchWay { SEARCH, SCAN, LABEL, GENERIC, BY_HAND }

/**
 * A snackbar message; [undoRecordId] adds an "Undo" action that deletes that record and, when the log
 * changed the pantry, restores it to [pantryBefore].
 */
data class Message(val text: String, val undoRecordId: String? = null, val pantryBefore: List<PantryItem>? = null)

data class UiState(
    val screen: Screen = Screen.Home,
    /** Foods the user logs most, from any source, most used first. */
    val history: List<HistoryEntry> = emptyList(),
    /** Foods the user entered by hand or read from a label, sorted by name. */
    val myFoods: List<Product> = emptyList(),
    /** Opened packs the user keeps to eat later, newest first. */
    val pantry: List<PantryItem> = emptyList(),
    /** Entries this app logged today, newest first. */
    val today: List<LoggedEntry> = emptyList(),
    /** The bundled generic food list, loaded the first time generic search opens. */
    /** The bundled generic food list: null while loading, empty when it could not be read. */
    val genericFoods: List<GenericFood>? = null,
    val health: HealthStatus = HealthStatus.Checking,
    val message: Message? = null,
    /** A newer build published on GitHub, if any. */
    val update: AppRelease? = null,
    val updating: Boolean = false,
    val checkingUpdate: Boolean = false,
    /** The receipt being reviewed, kept while the user looks for a product for one of its lines. */
    val receipt: ReceiptDraft? = null,
    /** While set, the product the user picks answers this instead of opening the amount screen. */
    val matching: MatchTarget? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val sink = HealthConnectSink(application)
    private val repository = ProductRepository(USER_AGENT)
    /** The list before Food history existed; only read once to carry it over. */
    private val legacyRecentStore = TextFileStore(application, "recent.json")
    private val historyStore = TextFileStore(application, "history.json")
    private val myFoodsStore = TextFileStore(application, "myfoods.json")
    private val journalStore = TextFileStore(application, "journal.json")
    private val pantryStore = TextFileStore(application, "pantry.json")
    private var journal: List<LoggedEntry> = emptyList()
    private val updater = AppUpdater(application)
    private var lastUpdateCheck: Long? = null
    val labelReader = LabelReader(application)
    private val photoBarcodeReader = PhotoBarcodeReader(application)
    val receiptReader = ReceiptReader(application)
    private val receiptMatchesStore = TextFileStore(application, "receipt_matches.json")
    /** Products the user chose for receipt lines, by store and line. */
    private var receiptMatches: List<ReceiptMatch> = emptyList()
    /** Searches Open Food Facts for the receipt's unmatched lines, one at a time within the limit. */
    private var receiptSearchJob: Job? = null

    /** Open Food Facts results by normalized query, so repeating a search sends no request. */
    private val searchCache = object : LinkedHashMap<String, List<SearchHit>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchHit>>?) = size > SEARCH_CACHE_SIZE
    }

    /** When searches were sent ([SystemClock.elapsedRealtime]), to stay under the Open Food Facts limit. */
    private var searchTimes: List<Long> = emptyList()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            var historyText = historyStore.read()
            var myFoodsText = myFoodsStore.read()
            if (historyText.isBlank() && myFoodsText.isBlank()) {
                // First start with Food history: carry over the recent list, and keep the foods typed by hand in My foods.
                val legacy = FoodHistory.decode(legacyRecentStore.read(), now)
                historyText = FoodHistory.encode(legacy)
                myFoodsText = MyFoods.encode(
                    legacy.map { it.product }.filter { it.source == MANUAL_SOURCE }.fold(emptyList<Product>()) { list, p -> MyFoods.save(list, p) },
                )
                if (legacy.isNotEmpty()) {
                    historyStore.write(historyText)
                    myFoodsStore.write(myFoodsText)
                }
            }
            val history = FoodHistory.decode(historyText, now)
            val myFoods = MyFoods.decode(myFoodsText)
            journal = Journal.decode(journalStore.read())
            val pantry = Pantry.decode(pantryStore.read())
            receiptMatches = ReceiptMatching.decode(receiptMatchesStore.read())
            _state.update { it.copy(history = history, myFoods = myFoods, pantry = pantry) }
            refresh()
        }
    }

    /**
     * Looks for a newer GitHub release. Runs every time the app comes to the foreground (at most every
     * [UPDATE_CHECK_INTERVAL_MS], to stay inside GitHub's unauthenticated rate limit) and when the user
     * taps "Check for updates", which also reports "up to date" and errors.
     */
    fun checkForUpdate(manual: Boolean = false) {
        if (_state.value.checkingUpdate) return
        val now = SystemClock.elapsedRealtime()
        val last = lastUpdateCheck
        if (!manual && last != null && now - last < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheck = now
        _state.update { it.copy(checkingUpdate = true) }
        viewModelScope.launch {
            val result = updater.newerRelease()
            // A failed check keeps the banner from an earlier successful one.
            _state.update { it.copy(checkingUpdate = false, update = result.getOrElse { _ -> it.update }) }
            if (manual) result.fold(
                onSuccess = { release -> if (release == null) show("FoodLogger is up to date (build ${BuildConfig.VERSION_CODE})") },
                onFailure = { e -> show("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}") },
            )
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
            // A 2D code may hold only a web address or batch data, without the product number.
            show(if (raw.length > 14) "This code has no product number. Try the barcode, or Search." else "Unsupported barcode: $raw")
            return
        }
        // A pack already in the pantry is eaten from rather than opened again; "New pack" is offered there.
        // While matching a receipt line, the scanned product is the answer instead.
        if (_state.value.matching == null) {
            Pantry.find(_state.value.pantry, barcode)?.let {
                openPantryItem(it)
                return
            }
        }
        // The user's own entries and products logged before are re-used directly, which also works offline.
        findKnown(barcode)?.let {
            navigate(chosen(it))
            return
        }
        navigate(Screen.Loading(barcode))
        viewModelScope.launch {
            val result = repository.lookup(barcode, preferredLanguages())
            // Ignore the result if the user navigated away while it was loading.
            if (_state.value.screen != Screen.Loading(barcode)) return@launch
            val next = when (result) {
                is LookupResult.Found ->
                    if (result.product.per100g.isEmpty) {
                        Screen.Manual(result.product, "No nutrition values on Open Food Facts yet.")
                    } else {
                        chosen(result.product)
                    }
                LookupResult.NotFound ->
                    Screen.Manual(blankProduct(barcode), "Product $barcode is not on Open Food Facts.")
                is LookupResult.Failed ->
                    Screen.Manual(blankProduct(barcode), result.reason)
            }
            navigate(next)
        }
    }

    /** Looks up the product whose barcode is in the saved photo at [uri]. */
    fun scanBarcodePhoto(uri: Uri) {
        viewModelScope.launch {
            val barcode = try {
                photoBarcodeReader.read(uri)
            } catch (e: Exception) {
                // IOException for an unreadable image, MlKitException while the model is still downloading.
                show("Could not read the photo: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            if (barcode == null) show("No product barcode found in the photo.") else onScanned(barcode)
        }
    }

    private fun findKnown(barcode: String): Product? =
        _state.value.myFoods.firstOrNull { it.barcode == barcode }
            ?: _state.value.history.firstOrNull { it.product.barcode == barcode }?.product

    /** Packaged food without a usable barcode: the form with name, brand and "Scan nutrition label". */
    fun startLabelEntry() = navigate(Screen.Manual(blankProduct(null)))

    /** Generic food (fruit, bakery, home cooking): the form without brand or label. */
    fun startGenericEntry() = navigate(Screen.Manual(blankProduct(null), generic = true))

    fun editProduct(product: Product) = navigate(Screen.Manual(product, generic = GenericFoods.isGeneric(product)))

    /** Saves a food the user entered or corrected to My foods, then asks for the portion. */
    fun confirmManual(product: Product) {
        updateMyFoods(MyFoods.save(_state.value.myFoods, product))
        // Keep the history's copy in step, so it shows the corrected values too.
        updateHistory(FoodHistory.update(_state.value.history, product))
        navigate(chosen(product))
    }

    fun openSearch() = navigate(Screen.Search())

    fun setSearchQuery(query: String) = updateSearch { it.copy(query = query) }

    /** Narrows the results to [store]; Open Food Facts results already fetched are filtered again, not re-fetched. */
    fun setSearchStore(store: Store?) = updateSearch { it.copy(store = store) }

    /**
     * Searches Open Food Facts for the typed words. Only on request, since Open Food Facts allows about
     * 10 searches a minute: repeated searches come from [searchCache], and past [SearchLimit] the user is
     * asked to wait instead of risking a ban.
     */
    fun runSearch() {
        val screen = _state.value.screen as? Screen.Search ?: return
        val query = screen.query.trim()
        if (query.isEmpty() || screen.searching) return
        val key = FoodSearch.normalize(query)
        searchCache[key]?.let { hits ->
            updateSearch { it.copy(searchedQuery = query, hits = hits, error = null) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wait = SearchLimit.waitMillis(searchTimes, now)
        if (wait > 0) {
            val seconds = (wait + 999) / 1000
            updateSearch {
                it.copy(error = "Open Food Facts allows only a few searches a minute. Search again in $seconds s, or type more words to narrow the results below.")
            }
            return
        }
        searchTimes = SearchLimit.record(searchTimes, now)
        updateSearch { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            val result = repository.search(query, preferredLanguages())
            result.onSuccess { hits -> searchCache[key] = hits }
            updateSearch { current ->
                result.fold(
                    onSuccess = { hits -> current.copy(searching = false, searchedQuery = query, hits = hits) },
                    onFailure = { e -> current.copy(searching = false, error = e.message ?: e.javaClass.simpleName) },
                )
            }
        }
    }

    /** Opens a search result like a scanned product: the portion, or the form when it has no nutrition values. */
    fun selectSearchHit(product: Product) {
        val known = findKnown(product.barcode)
        when {
            known != null -> navigate(chosen(known))
            product.per100g.isEmpty -> navigate(Screen.Manual(product, "No nutrition values on Open Food Facts yet."))
            else -> navigate(chosen(product))
        }
    }

    /** Nothing found: the label form, with the typed words as the name and the chosen store as the brand. */
    fun readLabelFromSearch() {
        val screen = _state.value.screen as? Screen.Search
        navigate(Screen.Manual(blankProduct(null).copy(name = screen?.query?.trim().orEmpty(), brand = screen?.store?.label)))
    }

    private fun updateSearch(change: (Screen.Search) -> Screen.Search) = _state.update { state ->
        val current = state.screen as? Screen.Search
        if (current != null) state.copy(screen = change(current)) else state
    }

    fun openGenericSearch(query: String = "") {
        navigate(Screen.GenericSearch(query))
        if (!_state.value.genericFoods.isNullOrEmpty()) return
        _state.update { it.copy(genericFoods = null) }
        viewModelScope.launch { loadGenericFoods() }
    }

    /** The bundled generic food list, read once; empty when it cannot be read. */
    private suspend fun loadGenericFoods(): List<GenericFood> {
        _state.value.genericFoods?.takeIf { it.isNotEmpty() }?.let { return it }
        val foods = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().assets.open(GENERIC_FOODS_ASSET).bufferedReader().use { it.readText() } }
                .map { GenericFoods.parse(it).foods }
                .getOrDefault(emptyList())
        }
        _state.update { it.copy(genericFoods = foods) }
        return foods
    }

    fun setGenericQuery(query: String) = _state.update { state ->
        if (state.screen is Screen.GenericSearch) state.copy(screen = Screen.GenericSearch(query)) else state
    }

    /** Opens a generic food like any other product; a copy in the history (logged before) is the same data. */
    fun selectGenericFood(food: GenericFood) {
        val product = food.toProduct(preferredLanguages())
        navigate(chosen(findKnown(product.barcode) ?: product))
    }

    /** Nothing found: the generic form, with the typed words as the name. */
    fun enterGenericByHand() {
        val query = (_state.value.screen as? Screen.GenericSearch)?.query?.trim().orEmpty()
        navigate(Screen.Manual(blankProduct(null).copy(name = query), generic = true))
    }

    fun openMyFoods() = navigate(Screen.MyFoods)

    fun openToday() = navigate(Screen.Today)

    /** Deletes the user's own entry, and its line in the food history. */
    fun deleteMyFood(product: Product) {
        updateMyFoods(MyFoods.remove(_state.value.myFoods, product.barcode))
        updateHistory(FoodHistory.remove(_state.value.history, product.barcode))
    }

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

    fun selectFood(product: Product) = navigate(chosen(product))

    /**
     * Opens the amount screen to change a logged entry's amount or meal. Uses the food's history or
     * My foods copy when its name matches; otherwise rebuilds per-100 g values from the entry.
     */
    fun editEntry(entry: LoggedEntry) {
        val known = (_state.value.history.map { it.product } + _state.value.myFoods).firstOrNull { it.name == entry.name }
        val product = known ?: Product(
            barcode = "journal-${entry.name}",
            name = entry.name,
            per100g = if (entry.grams > 0) entry.nutrients.scaled(100 / entry.grams) else entry.nutrients,
            source = MANUAL_SOURCE,
        )
        navigate(Screen.Portion(product, editing = entry))
    }

    fun removeFromHistory(product: Product) = updateHistory(FoodHistory.remove(_state.value.history, product.barcode))

    /** Back: from looking for a receipt line's product to the receipt, otherwise home. */
    fun goHome() {
        val matching = _state.value.matching
        _state.update { it.copy(matching = null) }
        navigate(if (matching is MatchTarget.Row && _state.value.receipt != null) Screen.Receipt else Screen.Home)
    }

    /**
     * Logs [grams] of [product]. With [pantryId] the grams are taken from that pantry pack; with
     * [keepGramsLeft] the rest of a newly opened pack is kept in the pantry.
     */
    fun log(product: Product, grams: Double, meal: MealSlot, keepGramsLeft: Double? = null, pantryId: String? = null) {
        val editing = (_state.value.screen as? Screen.Portion)?.editing
        viewModelScope.launch {
            // An edited entry keeps its original time; FoodSink can only add and delete, so it is replaced.
            val time = editing?.let { Instant.ofEpochMilli(it.loggedAtMillis) } ?: Instant.now()
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
            if (editing != null) {
                // An edit changes only the entry: the history count and the pantry stay as they were.
                try {
                    sink.delete(editing.recordId)
                    saveJournal(Journal.remove(journal, editing.recordId))
                    show("Changed to ${formatGrams(grams)} g of ${product.name}")
                } catch (e: Exception) {
                    show("Saved the change, but could not remove the old entry: ${e.message ?: e.javaClass.simpleName}")
                }
                showToday()
                _state.update { it.copy(screen = Screen.Today) }
                return@launch
            }
            showToday()
            updateHistory(FoodHistory.record(_state.value.history, product, time.toEpochMilli()))
            val pantryBefore = _state.value.pantry
            val pantryAfter = when {
                pantryId != null -> Pantry.eat(pantryBefore, pantryId, grams)
                keepGramsLeft != null -> Pantry.keep(
                    pantryBefore,
                    PantryItem(
                        id = UUID.randomUUID().toString(),
                        product = product,
                        gramsLeft = keepGramsLeft,
                        totalGrams = product.packageGrams ?: (grams + keepGramsLeft),
                        openedAtMillis = time.toEpochMilli(),
                    ),
                )
                else -> pantryBefore
            }
            if (pantryAfter != pantryBefore) updatePantry(pantryAfter)
            _state.update { it.copy(screen = Screen.Home) }
            val finished = pantryId != null && pantryAfter.none { it.id == pantryId }
            show(
                if (finished) "Logged ${formatGrams(grams)} g. Finished ${product.name}." else "Logged ${formatGrams(grams)} g of ${product.name}",
                undoRecordId = recordId,
                pantryBefore = pantryBefore.takeIf { pantryAfter != it },
            )
        }
    }

    /** Logs one serving from a pantry pack for the current meal; packs without a serving size ask for the amount. */
    fun eatOne(item: PantryItem) {
        if (item.needsMatch) return matchPantryItem(item)
        val grams = item.oneServingGrams ?: return openPantryItem(item)
        log(item.product, grams, defaultMeal(), pantryId = item.id)
    }

    fun openPantryItem(item: PantryItem) {
        if (item.needsMatch) return matchPantryItem(item)
        navigate(Screen.Portion(item.product, item.id))
    }

    /** A pack from a receipt without a product yet: search for it first, then log from it. */
    private fun matchPantryItem(item: PantryItem) {
        _state.update { it.copy(matching = MatchTarget.Pack(item.id, item.product.name)) }
        navigate(Screen.Search(query = ReceiptMatching.query(item.product.name)))
        runSearch()
    }

    /** Logs from a fresh pack of a product that is already in the pantry. */
    fun newPack(product: Product) = navigate(Screen.Portion(product))

    /** Removes a pack from the pantry; what was logged from it stays logged. */
    fun deletePantryItem(item: PantryItem) = updatePantry(Pantry.remove(_state.value.pantry, item.id))

    /** The snackbar's "Undo": deletes the record and puts the pantry back as it was before that log. */
    fun undo(message: Message) {
        val recordId = message.undoRecordId ?: return
        viewModelScope.launch {
            if (deleteRecord(recordId)) message.pantryBefore?.let(::updatePantry)
        }
    }

    /** Deletes a logged entry from Health Connect and the local journal. */
    fun delete(recordId: String) {
        viewModelScope.launch { deleteRecord(recordId) }
    }

    private suspend fun deleteRecord(recordId: String): Boolean {
        try {
            sink.delete(recordId)
        } catch (e: Exception) {
            show("Could not delete: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        saveJournal(Journal.remove(journal, recordId))
        showToday()
        return true
    }

    /**
     * Where a product the user picked goes: the amount screen, or, while matching, the receipt row or pantry
     * pack it was picked for. The match is remembered for that store and receipt line.
     */
    private fun chosen(product: Product): Screen = when (val target = _state.value.matching) {
        null -> Screen.Portion(product)
        is MatchTarget.Row -> {
            _state.update { it.copy(matching = null) }
            setRowMatch(target.rowId, product)
            if (_state.value.receipt != null) Screen.Receipt else Screen.Portion(product)
        }
        is MatchTarget.Pack -> {
            _state.update { it.copy(matching = null) }
            val item = _state.value.pantry.firstOrNull { it.id == target.pantryId }
            if (item == null) {
                Screen.Portion(product)
            } else {
                updatePantry(Pantry.match(_state.value.pantry, item.id, product))
                item.receiptKey?.let { rememberMatches(listOf(it to product)) }
                Screen.Portion(product, item.id)
            }
        }
    }

    /** Reads the receipt at [uri] (a PDF or a photo) and opens its checklist. */
    fun openReceipt(uri: Uri, mimeType: String?) {
        receiptSearchJob?.cancel()
        _state.update { it.copy(receipt = null, matching = null, screen = Screen.ReceiptReading) }
        viewModelScope.launch {
            val lines = try {
                receiptReader.read(uri, mimeType)
            } catch (e: Exception) {
                // IOException for an unreadable file, MlKitException while the model is still downloading.
                if (_state.value.screen == Screen.ReceiptReading) navigate(Screen.Home)
                show("Could not read the receipt: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            val receipt = Receipts.parse(lines)
            if (_state.value.screen != Screen.ReceiptReading) return@launch
            if (receipt.lines.isEmpty()) {
                navigate(Screen.Home)
                show("No items found on this receipt.")
                return@launch
            }
            val foods = loadGenericFoods()
            _state.update { it.copy(receipt = ReceiptDraft(receipt.store, receipt.date, receiptRows(receipt, foods)), screen = Screen.Receipt) }
            startReceiptSearches()
        }
    }

    /** Matches each line from what is on the phone: remembered choices, the user's own foods, generic foods. */
    private fun receiptRows(receipt: Receipt, foods: List<GenericFood>): List<ReceiptRow> {
        val own = (_state.value.myFoods + _state.value.history.map { it.product } + _state.value.pantry.map { it.product })
            .filterNot { ReceiptMatching.needsMatch(it) }
        return receipt.lines.mapIndexed { index, line ->
            val key = ReceiptMatching.key(receipt.store, line)
            val remembered = ReceiptMatching.find(receiptMatches, key)
            val local = if (remembered == null) ReceiptMatching.guesses(line, own) else emptyList()
            val product = remembered ?: local.firstOrNull()?.takeIf { ReceiptMatching.confident(line, it) }
            // Loose and weighed food (fruit, meat) is usually a generic food rather than a packaged product.
            val generic = if (product == null && (line.weighed || line.pack == null)) genericGuesses(foods, line) else emptyList()
            val ticked = line.food != false
            ReceiptRow(
                id = index,
                line = line,
                key = key,
                ticked = ticked,
                product = product,
                guesses = if (product != null) emptyList() else (local + generic).distinctBy { it.barcode }.take(MAX_GUESSES),
                grams = rowGrams(line, product)?.let(::formatGrams).orEmpty(),
                queued = product == null && ticked && !line.weighed && ReceiptMatching.query(line.name).isNotEmpty(),
            )
        }
    }

    /** Generic foods named like the line: all its words, else its longest word that finds something. */
    private fun genericGuesses(foods: List<GenericFood>, line: ReceiptLine): List<Product> {
        val words = ReceiptMatching.queryWords(line.name)
        if (words.isEmpty()) return emptyList()
        val languages = preferredLanguages()
        val single = words.sortedByDescending { it.length }.flatMap { listOf(it, it.removeSuffix("s")) }.filter { it.length >= 4 }
        val query = (listOf(words.joinToString(" ")) + single).distinct()
            .firstOrNull { GenericFoods.search(foods, it, languages, limit = 1).isNotEmpty() } ?: return emptyList()
        return GenericFoods.search(foods, query, languages, limit = MAX_GUESSES).map { food ->
            food.toProduct(languages).let { findKnown(it.barcode) ?: it }
        }
    }

    private fun rowGrams(line: ReceiptLine, product: Product?): Double? =
        line.grams ?: product?.packageGrams?.let { it * line.packs }

    /** Searches Open Food Facts for the queued lines in turn, waiting whenever the search limit is reached. */
    private fun startReceiptSearches() {
        receiptSearchJob?.cancel()
        receiptSearchJob = viewModelScope.launch {
            while (true) {
                val draft = _state.value.receipt ?: break
                val row = draft.rows.firstOrNull { it.queued } ?: break
                if (row.product != null || !row.ticked) {
                    updateRow(row.id) { it.copy(queued = false) }
                    continue
                }
                val query = ReceiptMatching.query(row.line.name)
                val key = FoodSearch.normalize(query)
                val hits = searchCache[key] ?: run {
                    val wait = SearchLimit.waitMillis(searchTimes, SystemClock.elapsedRealtime())
                    if (wait > 0) delay(wait)
                    searchTimes = SearchLimit.record(searchTimes, SystemClock.elapsedRealtime())
                    repository.search(query, preferredLanguages()).getOrNull()?.also { searchCache[key] = it }
                }.orEmpty()
                val withNutrition = hits.filter { it.hasNutrition }
                // Store data on Open Food Facts is patchy, so the store only narrows when it leaves something.
                val found = FoodSearch.remote(withNutrition, draft.store).ifEmpty { withNutrition }
                    .map { it.product }
                    .filterNot { ReceiptMatching.packDisagrees(row.line, it) }
                    .sortedByDescending { ReceiptMatching.score(row.line, it) }
                updateRow(row.id) { current ->
                    current.copy(queued = false, guesses = (current.guesses + found).distinctBy { it.barcode }.take(MAX_GUESSES))
                }
            }
        }
    }

    private fun updateRow(id: Int, change: (ReceiptRow) -> ReceiptRow) = _state.update { state ->
        val draft = state.receipt ?: return@update state
        state.copy(receipt = draft.copy(rows = draft.rows.map { if (it.id == id) change(it) else it }))
    }

    fun toggleRow(id: Int) = updateRow(id) { it.copy(ticked = !it.ticked) }

    fun setRowGrams(id: Int, text: String) = updateRow(id) { it.copy(grams = text) }

    /** The user picked [product] for a row, from its guesses or by searching. */
    fun pickForRow(id: Int, product: Product) {
        _state.update { it.copy(matching = null) }
        setRowMatch(id, product)
    }

    private fun setRowMatch(id: Int, product: Product) = updateRow(id) { row ->
        row.copy(
            product = product,
            ticked = true,
            rejected = false,
            queued = false,
            grams = row.grams.ifBlank { rowGrams(row.line, product)?.let(::formatGrams).orEmpty() },
        )
    }

    /** "None of these" or "Change": offers the ways to find the product instead. */
    fun rejectRowMatch(id: Int) = updateRow(id) { it.copy(product = null, rejected = true, queued = false) }

    /** Opens [way] to find the product for row [id]; for [MatchWay.SCAN] the screen then starts the scanner. */
    fun matchRow(id: Int, way: MatchWay) {
        val draft = _state.value.receipt ?: return
        val row = draft.rows.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(matching = MatchTarget.Row(id, row.line.name)) }
        val query = ReceiptMatching.query(row.line.name)
        when (way) {
            MatchWay.SEARCH -> {
                navigate(Screen.Search(query = query))
                runSearch()
            }
            MatchWay.SCAN -> Unit
            MatchWay.LABEL -> navigate(Screen.Manual(blankProduct(null).copy(name = row.line.name, brand = draft.store?.label)))
            MatchWay.GENERIC -> openGenericSearch(query)
            MatchWay.BY_HAND -> navigate(Screen.Manual(blankProduct(null).copy(name = query), generic = true))
        }
    }

    /** Puts the ticked rows into the pantry as unopened packs; unmatched ones as "needs a match". */
    fun addReceiptToPantry() {
        val draft = _state.value.receipt ?: return
        val rows = draft.rows.filter { it.ticked }
        if (rows.isEmpty()) {
            show("Tick the items to keep in the pantry.")
            return
        }
        rows.firstOrNull { it.gramsValue == null }?.let {
            show("Enter the grams for ${it.line.name}.")
            return
        }
        val now = System.currentTimeMillis()
        val bought = draft.date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val items = rows.map { row ->
            val grams = row.gramsValue!!
            PantryItem(
                id = UUID.randomUUID().toString(),
                product = row.product ?: ReceiptMatching.placeholder(draft.store, row.line),
                gramsLeft = grams,
                totalGrams = grams,
                openedAtMillis = now,
                opened = false,
                boughtAtMillis = bought,
                receiptKey = row.key,
            )
        }
        rememberMatches(rows.mapNotNull { row -> row.product?.let { row.key to it } })
        updatePantry(Pantry.addBought(_state.value.pantry, items))
        discardReceipt()
        val unmatched = items.count { it.needsMatch }
        val added = if (items.size == 1) "Added 1 item to the pantry" else "Added ${items.size} items to the pantry"
        show(if (unmatched == 0) "$added." else "$added; $unmatched still need a match.")
    }

    fun discardReceipt() {
        receiptSearchJob?.cancel()
        _state.update { it.copy(receipt = null, matching = null, screen = Screen.Home) }
    }

    private fun rememberMatches(choices: List<Pair<String, Product>>) {
        if (choices.isEmpty()) return
        receiptMatches = choices.fold(receiptMatches) { list, (key, product) -> ReceiptMatching.remember(list, key, product) }
        val text = ReceiptMatching.encode(receiptMatches)
        viewModelScope.launch { receiptMatchesStore.write(text) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun saveJournal(list: List<LoggedEntry>) {
        journal = list
        journalStore.write(Journal.encode(list))
    }

    fun defaultMeal(): MealSlot = MealSlot.forHour(LocalTime.now().hour)

    private fun updateHistory(list: List<HistoryEntry>) {
        _state.update { it.copy(history = list) }
        viewModelScope.launch { historyStore.write(FoodHistory.encode(list)) }
    }

    private fun updatePantry(list: List<PantryItem>) {
        _state.update { it.copy(pantry = list) }
        viewModelScope.launch { pantryStore.write(Pantry.encode(list)) }
    }

    private fun updateMyFoods(list: List<Product>) {
        _state.update { it.copy(myFoods = list) }
        viewModelScope.launch { myFoodsStore.write(MyFoods.encode(list)) }
    }

    private fun navigate(screen: Screen) = _state.update { it.copy(screen = screen) }

    private fun show(text: String, undoRecordId: String? = null, pantryBefore: List<PantryItem>? = null) =
        _state.update { it.copy(message = Message(text, undoRecordId, pantryBefore)) }

    private fun blankProduct(barcode: String?) = Product(
        barcode = barcode ?: "manual-${UUID.randomUUID()}",
        name = "",
        source = MANUAL_SOURCE,
    )

    companion object {
        const val MANUAL_SOURCE = "Manual"
        private const val UPDATE_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val SEARCH_CACHE_SIZE = 20
        private const val MAX_GUESSES = 3
        private const val GENERIC_FOODS_ASSET = "generic_foods.json"
        private const val USER_AGENT = "FoodLogger-Android/0.1 (https://github.com/RLi43/FoodLogger)"

        /** Device languages first, then the Swiss national languages and English as fallbacks. */
        fun preferredLanguages(): List<String> {
            val locales = LocaleList.getDefault()
            val device = (0 until locales.size()).map { locales[it].language }
            return (device + listOf("de", "fr", "it", "en")).distinct()
        }
    }
}
