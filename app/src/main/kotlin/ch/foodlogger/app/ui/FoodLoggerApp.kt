package ch.foodlogger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.foodlogger.app.HealthStatus
import ch.foodlogger.app.MainViewModel
import ch.foodlogger.app.MatchWay
import ch.foodlogger.app.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodLoggerApp(
    viewModel: MainViewModel,
    onScan: () -> Unit,
    onScanPhoto: () -> Unit,
    onPhotographLabel: () -> Unit,
    onPickLabel: () -> Unit,
    onPickReceipt: () -> Unit,
    onPhotographReceipt: () -> Unit,
    onGrantPermission: () -> Unit,
    onInstallHealthConnect: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message.text,
            actionLabel = if (message.undoRecordId != null) "Undo" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(message)
        viewModel.messageShown()
    }
    BackHandler(enabled = state.screen != Screen.Home) {
        when {
            // Leaving an entry's edit returns to the Today page it was opened from.
            (state.screen as? Screen.Portion)?.editing != null -> viewModel.openToday()
            state.screen == Screen.Receipt || state.screen == Screen.ReceiptReading -> viewModel.discardReceipt()
            else -> viewModel.goHome()
        }
    }

    Scaffold(
        topBar = {
            // While looking for the product of a receipt line or pantry pack, the title says which one.
            val matching = state.matching?.takeIf { state.screen != Screen.Receipt }
            TopAppBar(title = { Text(matching?.let { "Product for: ${it.name}" } ?: titleFor(state.screen), maxLines = 1) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(
                state = state,
                onScan = onScan,
                onScanPhoto = onScanPhoto,
                onSearch = viewModel::openSearch,
                onReadLabel = viewModel::startLabelEntry,
                onGenericEntry = viewModel::startGenericEntry,
                onGenericSearch = { viewModel.openGenericSearch() },
                onPickReceipt = onPickReceipt,
                onPhotographReceipt = onPhotographReceipt,
                onMyFoods = viewModel::openMyFoods,
                onFood = viewModel::selectFood,
                onRemoveFromHistory = viewModel::removeFromHistory,
                onPantryItem = viewModel::openPantryItem,
                onEatOne = viewModel::eatOne,
                onDeletePantryItem = viewModel::deletePantryItem,
                onToday = viewModel::openToday,
                onInstallUpdate = viewModel::installUpdate,
                onCheckForUpdate = { viewModel.checkForUpdate(manual = true) },
                onGrantPermission = onGrantPermission,
                onInstallHealthConnect = onInstallHealthConnect,
                modifier = modifier,
            )
            is Screen.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Looking up ${screen.barcode}…")
                }
            }
            is Screen.Portion -> {
                // The pack may have been finished or deleted meanwhile; then this is a plain portion.
                val pantryItem = state.pantry.firstOrNull { it.id == screen.pantryId }
                PortionScreen(
                    product = pantryItem?.product ?: screen.product,
                    pantryItem = pantryItem,
                    defaultMeal = viewModel.defaultMeal(),
                    editing = screen.editing,
                    canLog = state.health == HealthStatus.Ready,
                    onLog = { product, grams, meal, keepGramsLeft -> viewModel.log(product, grams, meal, keepGramsLeft, pantryItem?.id) },
                    onEdit = { viewModel.editProduct(screen.product) },
                    onNewPack = { viewModel.newPack(screen.product) },
                    modifier = modifier,
                )
            }
            is Screen.Manual -> ManualEntryScreen(
                draft = screen.draft,
                hint = screen.hint,
                scan = screen.scan,
                scanId = screen.scanId,
                scanning = screen.scanning,
                generic = screen.generic,
                onPhotographLabel = onPhotographLabel,
                onPickLabel = onPickLabel,
                onContinue = viewModel::confirmManual,
                modifier = modifier,
            )
            is Screen.Search -> SearchScreen(
                screen = screen,
                ownFoods = state.myFoods + state.history.map { it.product },
                onQueryChange = viewModel::setSearchQuery,
                onStoreChange = viewModel::setSearchStore,
                onSearch = viewModel::runSearch,
                onSelect = viewModel::selectSearchHit,
                onReadLabel = viewModel::readLabelFromSearch,
                modifier = modifier,
            )
            is Screen.GenericSearch -> GenericSearchScreen(
                query = screen.query,
                foods = state.genericFoods,
                loggedBefore = state.history.map { it.product.barcode }.toSet(),
                languages = MainViewModel.preferredLanguages(),
                onQueryChange = viewModel::setGenericQuery,
                onSelect = viewModel::selectGenericFood,
                onEnterByHand = viewModel::enterGenericByHand,
                modifier = modifier,
            )
            Screen.MyFoods -> MyFoodsScreen(
                foods = state.myFoods,
                onSelect = viewModel::selectFood,
                onEdit = viewModel::editProduct,
                onDelete = viewModel::deleteMyFood,
                modifier = modifier,
            )
            Screen.ReceiptReading -> Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Reading the receipt…")
                }
            }
            Screen.Receipt -> state.receipt?.let { draft ->
                ReceiptScreen(
                    draft = draft,
                    onToggle = { viewModel.toggleRow(it.id) },
                    onGrams = { row, text -> viewModel.setRowGrams(row.id, text) },
                    onPick = { row, product -> viewModel.pickForRow(row.id, product) },
                    onReject = { viewModel.rejectRowMatch(it.id) },
                    onMatch = { row, way ->
                        viewModel.matchRow(row.id, way)
                        if (way == MatchWay.SCAN) onScan()
                    },
                    onAdd = viewModel::addReceiptToPantry,
                    onDiscard = viewModel::discardReceipt,
                    modifier = modifier,
                )
            }
            Screen.Today -> TodayScreen(
                entries = state.today,
                onEdit = viewModel::editEntry,
                onDelete = { viewModel.delete(it.recordId) },
                modifier = modifier,
            )
        }
    }
}

private fun titleFor(screen: Screen) = when (screen) {
    Screen.Home, is Screen.Loading -> "FoodLogger"
    is Screen.Portion -> "How much?"
    is Screen.Manual -> if (screen.generic) "Generic food" else "Nutrition per 100 g"
    is Screen.Search -> "Search packaged food"
    Screen.MyFoods -> "My foods"
    is Screen.GenericSearch -> "Search generic food"
    Screen.Today -> "Today"
    Screen.ReceiptReading, Screen.Receipt -> "Receipt"
}
