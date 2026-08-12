package com.alad1nks.jaiqal.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.alerts
import jaiqal.resources.generated.resources.plants
import jaiqal.resources.generated.resources.settings
import org.jetbrains.compose.resources.stringResource

enum class MainSection { PLANTS, ALERTS, SETTINGS }

@Composable
fun MainScaffold(
    selected: MainSection,
    onSelect: (MainSection) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val items = listOf(
        MainSection.PLANTS to stringResource(Res.string.plants),
        MainSection.ALERTS to stringResource(Res.string.alerts),
        MainSection.SETTINGS to stringResource(Res.string.settings),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { (section, label) ->
                    NavigationBarItem(
                        selected = selected == section,
                        onClick = { onSelect(section) },
                        icon = { Text(if (selected == section) "●" else "○", Modifier.semantics { contentDescription = label }) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding -> content(padding) }
}

fun Modifier.withMainContentPadding(padding: PaddingValues) = padding(padding)
