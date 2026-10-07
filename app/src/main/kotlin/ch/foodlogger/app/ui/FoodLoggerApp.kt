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
import ch.foodlogger.app.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodLoggerApp(
    viewModel: MainViewModel,
    onScan: () -> Unit,
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
        if (result == SnackbarResult.ActionPerformed) message.undoRecordId?.let(viewModel::delete)
        viewModel.messageShown()
    }
    BackHandler(enabled = state.screen != Screen.Home) { viewModel.goHome() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(titleFor(state.screen)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(
                state = state,
                onScan = onScan,
                onManual = viewModel::startManualEntry,
                onRecent = viewModel::selectRecent,
                onRemoveRecent = viewModel::removeRecent,
                onDeleteEntry = { viewModel.delete(it.recordId) },
                onInstallUpdate = viewModel::installUpdate,
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
            is Screen.Portion -> PortionScreen(
                product = screen.product,
                defaultMeal = viewModel.defaultMeal(),
                canLog = state.health == HealthStatus.Ready,
                onLog = viewModel::log,
                onEdit = { viewModel.editProduct(screen.product) },
                modifier = modifier,
            )
            is Screen.Manual -> ManualEntryScreen(
                draft = screen.draft,
                hint = screen.hint,
                onContinue = viewModel::confirmManual,
                modifier = modifier,
            )
        }
    }
}

private fun titleFor(screen: Screen) = when (screen) {
    Screen.Home, is Screen.Loading -> "FoodLogger"
    is Screen.Portion -> "How much?"
    is Screen.Manual -> "Nutrition per 100 g"
}
