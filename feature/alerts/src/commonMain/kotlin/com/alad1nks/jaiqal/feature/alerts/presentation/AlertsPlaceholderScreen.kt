package com.alad1nks.jaiqal.feature.alerts.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.alad1nks.jaiqal.core.designsystem.component.EmptyState
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.alerts_empty_message
import jaiqal.resources.generated.resources.alerts_empty_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun AlertsPlaceholderScreen() {
    EmptyState(
        title = stringResource(Res.string.alerts_empty_title),
        message = stringResource(Res.string.alerts_empty_message),
        modifier = Modifier.fillMaxSize(),
    )
}
