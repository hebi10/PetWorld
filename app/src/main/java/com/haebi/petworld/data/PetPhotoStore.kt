package com.haebi.petworld.data

import android.content.Context
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.photoDirectoryName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class PetPhotoStore(
    private val context: Context,
    private val scope: LocalAccountScope = LocalAccountScope.Guest,
) {
    private val directory get() = File(context.filesDir, scope.photoDirectoryName).apply { mkdirs() }

    fun fileFor(name: String?): File? {
        if (name == null || !Regex("pet_[a-z0-9-]+\\.jpg").matches(name)) return null
        return File(directory, name)
    }

    suspend fun importPhoto(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val type = context.contentResolver.getType(uri)
        require(type == null || type.startsWith("image/")) { "이미지 파일만 선택할 수 있습니다." }

        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                require(cursor.getLong(0) in 1..20_000_000) { "20MB 이하의 이미지를 선택해 주세요." }
            }
        }

        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, size) }
        require(size.outWidth in 1..10_000 && size.outHeight in 1..10_000) {
            "지원하지 않는 이미지 크기입니다."
        }
        val sample = generateSequence(1) { it * 2 }.takeWhile {
            size.outWidth / it > 1200 || size.outHeight / it > 1200
        }.lastOrNull() ?: 1
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("이미지 파일을 열 수 없습니다.")

        val name = "pet_${UUID.randomUUID()}.jpg"
        val target = File(directory, name)
        try {
            FileOutputStream(target).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)) {
                    "프로필 사진 저장에 실패했습니다."
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
        name
    }

    suspend fun delete(name: String?) = withContext(Dispatchers.IO) {
        fileFor(name)?.delete()
        Unit
    }
}
