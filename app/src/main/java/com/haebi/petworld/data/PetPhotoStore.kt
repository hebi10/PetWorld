package com.haebi.petworld.data

import android.content.Context
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.photoDirectoryName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.haebi.petworld.data.sync.cloudJpegEvidence
import com.haebi.petworld.data.sync.readCloudJpeg
import com.haebi.petworld.data.sync.validCloudPhotoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.roundToInt

private const val MAX_PHOTO_BYTES = 20_000_000L
private const val MAX_SOURCE_EDGE = 10_000
internal const val MAX_PHOTO_EDGE = 1_200

internal fun photoSampleSize(width: Int, height: Int): Int {
    require(width in 1..MAX_SOURCE_EDGE && height in 1..MAX_SOURCE_EDGE)
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= MAX_PHOTO_EDGE) sample *= 2
    return sample
}

internal fun photoTargetSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val longest = maxOf(width, height)
    if (longest <= MAX_PHOTO_EDGE) return width to height
    val scale = MAX_PHOTO_EDGE.toDouble() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

internal fun copyPhotoWithinLimit(
    input: InputStream,
    output: OutputStream,
    limit: Long = MAX_PHOTO_BYTES,
): Long {
    require(limit > 0)
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(read.toLong() <= limit - total) { "20MB 이하의 이미지를 선택해 주세요." }
        output.write(buffer, 0, read)
        total += read
    }
    require(total > 0) { "빈 이미지 파일입니다." }
    return total
}

internal fun photoOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
    }
}

class PetPhotoStore(
    private val context: Context,
    private val scope: LocalAccountScope = LocalAccountScope.Guest,
) {
    private val directory get() = File(context.filesDir, scope.photoDirectoryName).apply {
        check(isDirectory || mkdirs()) { "사진 저장 폴더를 준비하지 못했습니다." }
    }

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
                val size = cursor.getLong(0)
                require(size < 0 || size in 1..MAX_PHOTO_BYTES) {
                    "20MB 이하의 이미지를 선택해 주세요."
                }
            }
        }

        val source = File.createTempFile("pet-import-", ".tmp", context.cacheDir)
        var output: File? = null
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("이미지 파일을 열 수 없습니다.")
            input.use { stream ->
                FileOutputStream(source).use { destination ->
                    copyPhotoWithinLimit(stream, destination)
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth in 1..MAX_SOURCE_EDGE && bounds.outHeight in 1..MAX_SOURCE_EDGE) {
                "지원하지 않는 이미지 크기입니다."
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(source.path, options)
                ?: error("이미지 파일을 열 수 없습니다.")
            bitmaps.add(decoded)

            val orientation = try {
                ExifInterface(source.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: java.io.IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val matrix = photoOrientationMatrix(orientation)
            val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true,
            ).also { if (it !== decoded) bitmaps.add(it) }
            val (width, height) = photoTargetSize(oriented.width, oriented.height)
            val finalBitmap = if (width == oriented.width && height == oriented.height) oriented
                else Bitmap.createScaledBitmap(oriented, width, height, true)
                    .also { if (it !== oriented) bitmaps.add(it) }

            val name = "pet_${UUID.randomUUID()}.jpg"
            val folder = directory
            val pending = File.createTempFile(".pet-write-", ".tmp", folder)
            output = pending
            FileOutputStream(pending).use { stream ->
                check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)) {
                    "프로필 사진 저장에 실패했습니다."
                }
                stream.fd.sync()
            }
            val target = File(folder, name)
            check(!target.exists() && pending.renameTo(target)) { "사진 저장을 완료하지 못했습니다." }
            name
        } finally {
            bitmaps.asReversed().forEach { if (!it.isRecycled) it.recycle() }
            output?.delete()
            source.delete()
        }
    }

    /** Install verified cloud JPEG bytes without decoding/recompressing the original.
     * Never overwrite an existing file with different content.
     * Caller serializes file mutations using PetWorldMutationGate.
     */
    internal suspend fun installVerifiedJpeg(name: String, bytes: ByteArray, expectedSha256: String): Boolean =
        withContext(Dispatchers.IO) {
            require(scope is LocalAccountScope.SignedIn) { "Cloud photo installation requires account storage" }
            require(validCloudPhotoName(name))
            val evidence = cloudJpegEvidence(bytes)
            require(evidence.sha256 == expectedSha256)
            val target = requireNotNull(fileFor(name))
            if (target.exists()) {
                return@withContext try {
                    readCloudJpeg(target).evidence == evidence
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            val pending = File.createTempFile(".pet-download-", ".tmp", directory)
            try {
                FileOutputStream(pending).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                check(!target.exists() && pending.renameTo(target))
                true
            } finally {
                pending.delete()
            }
        }

    suspend fun delete(name: String?) = withContext(Dispatchers.IO) {
        fileFor(name)?.delete()
        Unit
    }
}
