package com.alad1nks.jaiqal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jaiqal.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class RootDestination { PLANTS, ALERTS, SETTINGS }

@Composable
fun App() {
    var dark by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(RootDestination.PLANTS) }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
            bottomBar = {
                NavigationBar {
                    RootDestination.entries.forEach { item ->
                        val label = when (item) {
                            RootDestination.PLANTS -> stringResource(Res.string.plants)
                            RootDestination.ALERTS -> stringResource(Res.string.alerts)
                            RootDestination.SETTINGS -> stringResource(Res.string.settings)
                        }
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(if (destination == item) "●" else "○") },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (destination) {
                RootDestination.PLANTS -> PlantsScreen(Modifier.padding(padding))
                RootDestination.ALERTS -> EmptyScreen(stringResource(Res.string.no_alerts), Modifier.padding(padding))
                RootDestination.SETTINGS -> SettingsScreen(dark, { dark = it }, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun PlantsScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.my_plants), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = {}) { Text(stringResource(Res.string.add)) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.no_plants), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.add_first_plant), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun EmptyScreen(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}

@Composable private fun SettingsScreen(dark: Boolean, onDarkChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.dark_theme)); Switch(dark, onDarkChange)
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.sign_out)) }
    }
}
