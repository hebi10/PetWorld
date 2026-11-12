package com.haebi.petworld.migration

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.photoDirectoryName
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.migration.FirstImportGuards
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FirstImportPreview(
    val petCount: Int,
    val recordCount: Int,
    val photoCount: Int,
    val matchingCloudDocuments: Int,
    val extraCloudDocuments: Int,
    val conflicts: Int,
    val sourceFingerprint: String,
    val accountFingerprint: String,
) {
    val canImport: Boolean get() = petCount > 0 && conflicts == 0
}

data class FirstImportResult(
    val confirmedPets: Int,
    val confirmedRecords: Int,
    val confirmedPhotos: Int,
)

class FirstImportConflict : IllegalStateException(
    "계정에 같은 ID의 다른 기록이나 사진이 있습니다. 기존 데이터를 변경하지 않았습니다."
)

/**
 * This is explicitly invoked from the development account screen after a fresh preview
 * and backup confirmation. It is not a background sync and never deletes Guest data.
 */
class FirebaseDevFirstImportService(private val context: Context) {
    private val gate = Mutex()
    private val firebase = FirebaseApp.getInstance()
    private val auth = FirebaseAuth.getInstance(firebase)
    private val firestore = FirebaseFirestore.getInstance(firebase)
    private val storage = FirebaseStorage.getInstance(firebase)
    private val checkpoint = context.getSharedPreferences("petworld_fb04_import", Context.MODE_PRIVATE)

    private data class PhotoEvidence(
        val petId: String,
        val name: String,
        val file: File,
        val bytes: Long,
        val digest: String,
    )

    private data class Snapshot(
        val pets: List<PetEntity>,
        val records: List<CareEntity>,
        val photos: List<PhotoEvidence>,
        val selectedPetId: String?,
        val fingerprint: String,
    )

    private data class RemoteReview(
        val matching: Int,
        val extra: Int,
        val conflicts: Int,
    )

    private val validPhotoName = Regex("pet_[0-9a-f-]{36}\\.jpg")
    private val maximumPhotoSize = 5L * 1024L * 1024L

    private fun checkedUid(): String {
        check(context.packageName == "com.haebi.petworld.dev")
        check(firebase.options.projectId == "petworld-dev")
        check(!firebase.options.storageBucket.isNullOrBlank()) {
            "개발 Firebase Storage 설정이 필요합니다."
        }
        val user = auth.currentUser
            ?: error("Google 계정으로 먼저 로그인해 주세요.")
        check(!user.isAnonymous && user.providerData.any { it.providerId == "google.com" }) {
            "Google 계정으로 로그인한 뒤에만 기록을 이전할 수 있습니다."
        }
        return user.uid.takeIf { it.isNotBlank() }
            ?: error("Google 계정 인증을 다시 확인해 주세요.")
    }

    private fun assertSameUser(uid: String) {
        check(checkedUid() == uid) {
            "이전 중 Google 계정이 변경됐습니다. 작업을 중단합니다."
        }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                if (n > 0) md.update(chunk, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun guestSnapshotLocked(): Snapshot = withContext(Dispatchers.IO) {
        val db = PetWorldDatabase.get(context, LocalAccountScope.Guest)
        val (pets, records) = db.withTransaction {
            db.petDao().list() to db.careDao().listAll()
        }
        val petIds = pets.map { it.id }.toSet()
        require(petIds.size == pets.size)
        require(records.map { it.id }.toSet().size == records.size)
        require(records.all { it.petId in petIds }) {
            "반려동물과 연결되지 않은 기록이 있습니다."
        }
        pets.forEach(FirstImportPayloads::pet)
        records.forEach(FirstImportPayloads::record)
        val selected = ThemePreferences(context, LocalAccountScope.Guest).selectedPetId.first()
        require(selected == null || selected in petIds) {
            "선택된 반려동물 정보를 확인해 주세요."
        }

        val perPet = mutableSetOf<Pair<String, String>>()
        pets.forEach { pet ->
            pet.photoFile?.let { perPet += pet.id to it }
        }
        records.forEach { record ->
            record.photoFile?.let { perPet += record.petId to it }
        }
        val directory = File(context.filesDir, LocalAccountScope.Guest.photoDirectoryName)
        val photos = perPet.sortedWith(compareBy({ it.first }, { it.second })).map { (petId, name) ->
            require(validPhotoName.matches(name)) { "저장된 사진 이름을 확인해 주세요." }
            val file = File(directory, name)
            require(file.isFile && file.canRead()) { "참조된 사진을 읽지 못했습니다." }
            require(file.length() in 1..maximumPhotoSize) {
                "사진이 누락됐거나 클라우드 허용 크기(5MB)를 초과했습니다."
            }
            PhotoEvidence(petId, name, file, file.length(), digest(file))
        }
        val photoHashes = photos.associate { "${it.petId}/${it.name}" to it.digest }
        Snapshot(
            pets = pets.sortedBy { it.id },
            records = records.sortedBy { it.id },
            photos = photos,
            selectedPetId = selected,
            fingerprint = FirstImportPayloads.fingerprint(pets, records, photoHashes, selected),
        )
    }

    private suspend fun remoteReview(uid: String, source: Snapshot): RemoteReview {
        val petsRef = firestore.collection("users").document(uid).collection("pets")
        val cloudPets = petsRef.get(Source.SERVER).awaitImport().documents
        val cloudPetIds = cloudPets.map { it.id }.toSet()
        val localIds = source.pets.map { it.id }.toSet()
        var matching = 0
        var extra = cloudPetIds.count { it !in localIds }
        var conflicts = 0

        for (pet in source.pets) {
            assertSameUser(uid)
            val ref = petsRef.document(pet.id)
            val remote = cloudPets.firstOrNull { it.id == pet.id }
            if (remote != null) {
                if (FirstImportPayloads.sameRemote(FirstImportPayloads.pet(pet), remote.data)) {
                    matching++
                } else {
                    conflicts++
                }
            }
            val existingRecords = ref.collection("records").get(Source.SERVER).awaitImport().documents
            val localRecords = source.records.filter { it.petId == pet.id }
            val expectedIds = localRecords.map { it.id }.toSet()
            extra += existingRecords.count { it.id !in expectedIds }
            for (record in localRecords) {
                val existing = existingRecords.firstOrNull { it.id == record.id }
                if (existing != null) {
                    if (FirstImportPayloads.sameRemote(FirstImportPayloads.record(record), existing.data)) {
                        matching++
                    } else {
                        conflicts++
                    }
                }
            }
        }

        for (photo in source.photos) {
            assertSameUser(uid)
            val ref = photoRef(uid, photo)
            if (photoExists(ref)) {
                if (remotePhotoMatches(ref, photo)) matching++ else conflicts++
            }
        }
        return RemoteReview(matching, extra, conflicts)
    }

    private fun photoRef(uid: String, photo: PhotoEvidence) = storage.reference
        .child("users/${uid}/pets/${photo.petId}/photos/${photo.name}")

    private suspend fun photoExists(ref: com.google.firebase.storage.StorageReference): Boolean =
        try {
            ref.metadata.awaitImport()
            true
        } catch (error: StorageException) {
            if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw error
        }

    private suspend fun remotePhotoMatches(
        ref: com.google.firebase.storage.StorageReference,
        photo: PhotoEvidence,
    ): Boolean {
        val metadata = ref.metadata.awaitImport()
        if (metadata.sizeBytes != photo.bytes || metadata.contentType != "image/jpeg") return false
        val recordedHash = metadata.getCustomMetadata("sha256")
        if (recordedHash != null && recordedHash != photo.digest) return false
        val downloaded = ref.getBytes(maximumPhotoSize).awaitImport()
        return downloaded.size.toLong() == photo.bytes &&
            hex(MessageDigest.getInstance("SHA-256").digest(downloaded)) == photo.digest
    }

    suspend fun preview(): FirstImportPreview = gate.withLock {
        val uid = checkedUid()
        val source = PetWorldMutationGate.mutex.withLock { guestSnapshotLocked() }
        val remote = remoteReview(uid, source)
        assertSameUser(uid)
        FirstImportPreview(
            petCount = source.pets.size,
            recordCount = source.records.size,
            photoCount = source.photos.size,
            matchingCloudDocuments = remote.matching,
            extraCloudDocuments = remote.extra,
            conflicts = remote.conflicts,
            sourceFingerprint = source.fingerprint,
            accountFingerprint = FirstImportGuards.accountFingerprint(uid),
        )
    }

    suspend fun commit(
        preview: FirstImportPreview,
        encryptedBackupConfirmed: Boolean,
        allowCloudExtras: Boolean,
        onProgress: (String) -> Unit,
    ): FirstImportResult = gate.withLock {
        require(encryptedBackupConfirmed) {
            "기기 기록의 암호화 백업을 먼저 만들고 확인해 주세요."
        }
        require(preview.canImport) {
            "이전할 기록이 없거나 충돌이 있어 진행할 수 없습니다."
        }
        require(preview.extraCloudDocuments == 0 || allowCloudExtras) {
            "계정에 다른 데이터가 있습니다. 추가 방식의 이전을 선택해 주세요."
        }
        val uid = checkedUid()
        FirstImportGuards.requireSameAccount(preview.accountFingerprint, uid)
        val onlineUid = auth.currentUser?.also { user ->
            user.getIdToken(true).awaitImport()
        }?.uid
        check(onlineUid == uid) {
            "Firebase 인증이 변경됐습니다. 다시 로그인하고 미리보기를 확인해 주세요."
        }
        PetWorldMutationGate.mutex.withLock {
            val source = guestSnapshotLocked()
            FirstImportGuards.requireFreshSource(preview.sourceFingerprint, source.fingerprint)
            val current = remoteReview(uid, source)
            if (current.conflicts > 0) throw FirstImportConflict()
            FirstImportGuards.requireFreshReview(
                approvedMatching = preview.matchingCloudDocuments,
                approvedExtra = preview.extraCloudDocuments,
                approvedConflicts = preview.conflicts,
                latestMatching = current.matching,
                latestExtra = current.extra,
                latestConflicts = current.conflicts,
                allowCloudExtras = allowCloudExtras,
            )
            val scope = LocalAccountScope.fromFirebaseUid(uid) as LocalAccountScope.SignedIn
            check(checkpoint.edit().putString(scope.key, source.fingerprint).commit()) {
                "재시도 체크포인트를 저장하지 못했습니다."
            }
            assertSameUser(uid)
            onProgress("사진 무결성 확인·복사")
            for (photo in source.photos) {
                assertSameUser(uid)
                require(photo.file.length() == photo.bytes && digest(photo.file) == photo.digest) {
                    "업로드 전 사진이 변경됐습니다. 다시 미리보기를 진행해 주세요."
                }
                val target = photoRef(uid, photo)
                if (!photoExists(target)) {
                    val metadata = StorageMetadata.Builder()
                        .setContentType("image/jpeg")
                        .setCustomMetadata("sha256", photo.digest)
                        .build()
                    try {
                        target.putFile(Uri.fromFile(photo.file), metadata).awaitImport()
                    } catch (error: Exception) {
                        // A competing upload may have succeeded. Only matching bytes are reusable.
                        if (!photoExists(target) || !remotePhotoMatches(target, photo)) throw error
                    }
                }
                if (!remotePhotoMatches(target, photo)) throw FirstImportConflict()
            }
            assertSameUser(uid)
            onProgress("Firestore 기록 복사")
            val userRef = firestore.collection("users").document(uid)
            ensureUser(uid, userRef)
            for (pet in source.pets) {
                assertSameUser(uid)
                createIfAbsentOrEqual(userRef.collection("pets").document(pet.id),
                    FirstImportPayloads.pet(pet))
            }
            for (record in source.records) {
                assertSameUser(uid)
                createIfAbsentOrEqual(
                    userRef.collection("pets").document(record.petId).collection("records")
                        .document(record.id),
                    FirstImportPayloads.record(record),
                )
            }

            onProgress("서버 재조회 및 원본 대조")
            val after = remoteReview(uid, source)
            if (after.conflicts > 0 ||
                after.matching != source.pets.size + source.records.size + source.photos.size
            ) throw FirstImportConflict()
            require(guestSnapshotLocked().fingerprint == source.fingerprint) {
                "이전 중 원본 자료가 변경됐습니다. 자료를 삭제하지 않고 중단합니다."
            }
            markReady(uid, userRef)
            check(checkpoint.edit().remove(scope.key).commit()) {
                "서버 검증 후 로컬 체크포인트를 정리하지 못했습니다."
            }
            onProgress("원본 보존·서버 재조회 완료")
            FirstImportResult(
                confirmedPets = source.pets.size,
                confirmedRecords = source.records.size,
                confirmedPhotos = source.photos.size,
            )
        }
    }

    private suspend fun ensureUser(uid: String, ref: DocumentReference) {
        assertSameUser(uid)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(ref)
            if (!existing.exists()) {
                transaction.set(ref, mapOf(
                    "schemaVersion" to 1,
                    "createdAtMs" to System.currentTimeMillis(),
                    "migrationState" to "pending",
                    "revision" to 0,
                ))
            }
            true
        }.awaitImport()
    }

    private suspend fun createIfAbsentOrEqual(
        ref: DocumentReference,
        expected: Map<String, Any?>,
    ) {
        firestore.runTransaction { transaction ->
            val current = transaction.get(ref)
            if (current.exists()) {
                if (!FirstImportPayloads.sameRemote(expected, current.data)) {
                    throw FirstImportConflict()
                }
            } else {
                transaction.set(ref, expected)
            }
            true
        }.awaitImport()
    }

    private suspend fun markReady(uid: String, ref: DocumentReference) {
        assertSameUser(uid)
        firestore.runTransaction { transaction ->
            val current = transaction.get(ref)
            check(current.exists()) { "이전 상태 문서를 찾을 수 없습니다." }
            if (current.getString("migrationState") != "ready") {
                val revision = current.getLong("revision")
                    ?: error("이전 상태 버전이 없습니다.")
                transaction.update(ref, mapOf(
                    "migrationState" to "ready",
                    "revision" to revision + 1,
                ))
            }
            true
        }.awaitImport()
    }
}

private suspend fun <T> Task<T>.awaitImport(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (completed.isSuccessful) {
            try {
                continuation.resume(completed.result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        } else {
            continuation.resumeWithException(
                completed.exception ?: IllegalStateException("Firebase 요청을 완료하지 못했습니다.")
            )
        }
    }
}
