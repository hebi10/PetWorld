package com.haebi.petworld.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.haebi.petworld.ui.theme.PetSpacing

@Composable
fun PetSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(PetSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.small),
        ) {
            content()
        }
    }
}

@Composable
fun PetPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PetSpacing.minTouchTarget),
        shape = RectangleShape,
        elevation = null,
        contentPadding = PaddingValues(horizontal = PetSpacing.large),
    ) {
        Text(label)
    }
}

@Composable
fun PetSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = PetSpacing.minTouchTarget),
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = PetSpacing.large),
    ) {
        Text(label)
    }
}

@Composable
fun PetEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = PetSpacing.xxLarge),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

enum class PetStatus {
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun PetStatusLabel(
    text: String,
    status: PetStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        PetStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        PetStatus.SUCCESS -> Color(0xFF187447)
        PetStatus.WARNING -> Color(0xFF966217)
        PetStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (status == PetStatus.SUCCESS && androidx.compose.foundation.isSystemInDarkTheme()) {
            Color(0xFF78D9AA)
        } else if (status == PetStatus.WARNING && androidx.compose.foundation.isSystemInDarkTheme()) {
            Color(0xFFFFD28B)
        } else {
            color
        },
    )
}
