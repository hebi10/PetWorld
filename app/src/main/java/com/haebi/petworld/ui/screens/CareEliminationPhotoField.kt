package com.haebi.petworld.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.theme.PetSpacing

@Composable
internal fun CareEliminationPhotoField(
    photoStore: PetPhotoStore,
    photoFile: String?,
    previewUri: String?,
    removePhoto: Boolean,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val hasPhoto = previewUri != null || (photoFile != null && !removePhoto)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
    ) {
        Text("관찰 사진 (선택)", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PetAvatar(
                photoStore = photoStore,
                name = "배변 관찰",
                photoFile = photoFile.takeUnless { removePhoto },
                previewUri = previewUri,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = if (hasPhoto) "관찰 사진 미리보기" else "관찰 사진 없음"
                },
                size = 64.dp,
                shape = RectangleShape,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
            ) {
                Text(
                    if (hasPhoto) "사진 첨부됨" else "사진 없음",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PetSecondaryButton(
                    label = if (hasPhoto) "사진 변경" else "사진 선택",
                    onClick = onChoosePhoto,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasPhoto) {
                    TextButton(
                        onClick = onRemovePhoto,
                        modifier = Modifier.heightIn(min = PetSpacing.minTouchTarget),
                    ) {
                        Text("관찰 사진 제거")
                    }
                }
            }
        }
    }
}
