package com.haebi.petworld.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.ui.icons.PetIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PetAvatar(
    photoStore: PetPhotoStore,
    name: String,
    photoFile: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    previewUri: String? = null,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photoFile, previewUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val openStream = {
                    if (previewUri != null) context.contentResolver.openInputStream(Uri.parse(previewUri))
                    else photoStore.fileFor(photoFile)?.inputStream()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1)
                openStream()?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = sample
                    })
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "${name}의 프로필 사진",
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = PetIcons.Profile,
                contentDescription = "${name}의 기본 프로필 아이콘",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
