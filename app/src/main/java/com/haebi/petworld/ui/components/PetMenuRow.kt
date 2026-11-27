package com.haebi.petworld.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.theme.PetSpacing
import androidx.compose.ui.unit.dp

@Composable
internal fun PetMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f)
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        color = if (emphasized) colors.surfaceContainer else colors.surface,
        shape = RectangleShape,
        border = if (emphasized) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PetSpacing.medium, vertical = PetSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = tint)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
            Icon(PetIcons.Chevron, contentDescription = null, tint = tint)
        }
    }
}
