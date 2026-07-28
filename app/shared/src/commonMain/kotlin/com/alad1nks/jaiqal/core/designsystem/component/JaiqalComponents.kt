package com.alad1nks.jaiqal.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme

@Composable
fun JaiqalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Text(text)
    }
}

@Composable
fun JaiqalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
    )
}

@Composable
fun JaiqalCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(JaiqalTheme.spacing.medium)) { content() }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    JaiqalCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(JaiqalTheme.spacing.small))
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

enum class StatusKind { SUCCESS, WARNING, ERROR, NEUTRAL }

@Composable
fun StatusBadge(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = when (kind) {
        StatusKind.SUCCESS -> Color(0xFFD5F5DD) to Color(0xFF155724)
        StatusKind.WARNING -> Color(0xFFFFEDC2) to Color(0xFF6B4B00)
        StatusKind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        StatusKind.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = colors.first, contentColor = colors.second) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    StateLayout(modifier) {
        CircularProgressIndicator(Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun EmptyState(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    StateLayout(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (action != null && onAction != null) JaiqalButton(action, onAction)
    }
}

@Composable
fun ErrorState(title: String, message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StateLayout(modifier) {
        StatusBadge(title, StatusKind.ERROR)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        JaiqalButton(retryLabel, onRetry)
    }
}

@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = JaiqalTheme.spacing.medium, vertical = JaiqalTheme.spacing.small),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StateLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(JaiqalTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(JaiqalTheme.spacing.medium),
    ) {
        content()
    }
}
