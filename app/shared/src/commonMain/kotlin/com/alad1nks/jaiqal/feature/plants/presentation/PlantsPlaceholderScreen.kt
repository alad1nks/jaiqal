package com.alad1nks.jaiqal.feature.plants.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.alad1nks.jaiqal.core.designsystem.component.EmptyState
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.add_plant
import jaiqal.app.shared.generated.resources.plants_empty_message
import jaiqal.app.shared.generated.resources.plants_empty_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlantsPlaceholderScreen() {
    EmptyState(
        title = stringResource(Res.string.plants_empty_title),
        message = stringResource(Res.string.plants_empty_message),
        action = stringResource(Res.string.add_plant),
        onAction = {},
        modifier = Modifier.fillMaxSize(),
    )
}
