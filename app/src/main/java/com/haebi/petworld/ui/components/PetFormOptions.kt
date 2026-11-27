package com.haebi.petworld.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.haebi.petworld.ui.theme.PetSpacing

@Composable
internal fun <T> PetFormOptions(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (options.isNotEmpty()) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f) {
                    minOf(options.size, 2)
                } else {
                    options.size
                }
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
                    options.chunked(columns).forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                        ) {
                            group.forEach { option ->
                                FilterChip(
                                    modifier = Modifier.weight(1f).heightIn(min = PetSpacing.minTouchTarget),
                                    selected = selected == option,
                                    enabled = enabled,
                                    onClick = { onSelect(option) },
                                    label = { Text(label(option), style = MaterialTheme.typography.bodyMedium) },
                                    shape = RectangleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
