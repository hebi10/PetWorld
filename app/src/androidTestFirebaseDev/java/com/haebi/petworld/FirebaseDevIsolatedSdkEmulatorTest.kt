package com.haebi.petworld

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source
import com.haebi.petworld.auth.FirebaseDevAccountMemoryCache
import com.haebi.petworld.auth.FirebaseDevAccountSdkSessions
import com.haebi.petworld.auth.FirebaseDevSdkBinding
import com.haebi.petworld.data.account.AccountCacheTransitionEvidence
import com.haebi.petworld.data.account.AccountSdkRouteGate
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.drainAndCloseIsolatedSdk
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * All Firebase SDK calls are against an explicitly named, test-only demo project.
 * The app's default FirebaseApp is never authenticated or used for fixture data.
 * Run only with the dedicated local Emulator Suite workflow.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseDevIsolatedSdkEmulatorTest {
    companion object {
        private const val PROJECT = "demo-petworld-sdk-qa"
        private const val HOST = "10.0.2.2"
        private const val AUTH_PORT = 9099
        private const val FIRESTORE_PORT = 8080
    }

    private class Fixture(context: Context, val label: String, memory: Boolean) {
        private val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT)
            .setApplicationId("1:1234567890:android:1111111111111111")
            .setApiKey("fake-ci-key")
            .build()
        val app = FirebaseApp.initializeApp(context, options, "sdk-qa-" + label + "-" + UUID.randomUUID())
        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)

        init {
            check(app.name.startsWith("sdk-qa-") && app.options.projectId == PROJECT &&
                app.name != FirebaseApp.DEFAULT_APP_NAME)
            auth.useEmulator(HOST, AUTH_PORT)
            db.useEmulator(HOST, FIRESTORE_PORT)
            db.firestoreSettings = if (memory) {
                FirebaseDevAccountMemoryCache.settings(db.firestoreSettings)
            } else {
                FirebaseFirestoreSettings.Builder(db.firestoreSettings)
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
            }
            check(db.firestoreSettings.host == "$HOST:$FIRESTORE_PORT" && !db.firestoreSettings.isSslEnabled)
            check(if (memory) db.firestoreSettings.cacheSettings is MemoryCacheSettings
                else db.firestoreSettings.cacheSettings is PersistentCacheSettings)
        }

        suspend fun signIn(subject: String): String {
            check(app.options.projectId == PROJECT)
            // Firebase's documented synthetic IDP token format, accepted by Auth emulator only.
            val mock = JSONObject().put("sub",subject).put("email","$subject@example.invalid")
                .put("email_verified",true).toString()
            val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(mock,null)).awaitQa()
            val user = requireNotNull(result.user)
            check(user.providerData.any { it.providerId == "google.com" })
            check(!user.getIdToken(true).awaitQa().token.isNullOrBlank())
            return user.uid
        }

        suspend fun finish(terminated: Boolean = false) {
            if (!terminated) runCatching { db.enableNetwork().awaitQa(); db.terminate().awaitQa() }
            auth.signOut()
            // Auth may still have internal callbacks referencing this named app.
            // Keep it alive until the disposable instrumentation process exits.
        }
    }

    private fun fixtureContext(): Context {
        val inst = InstrumentationRegistry.getInstrumentation()
        // A normal Firebase UI smoke run must never execute the local-only test suite.
        assumeTrue("Dedicated demo-project SDK QA only",
            InstrumentationRegistry.getArguments().getString("petworldLocalSdkQa") == "true")
        return inst.targetContext
    }

    @Test
    fun sameAccountBindingAndRealRulesRejectOtherAccountAndGuest() = runBlocking {
        val context = fixtureContext()
        val primary = Fixture(context,"legacy",false)
        val named = Fixture(context,"named-a",true)
        val other = Fixture(context,"named-b",true)
        try {
            val subject = "qa-" + UUID.randomUUID()
            val a = primary.signIn(subject)
            assertEquals(a,named.signIn(subject))
            val subjectB = "qa-" + UUID.randomUUID()
            val b = other.signIn(subjectB)
            assertNotEquals(a,b)
            val sa = LocalAccountScope.fromFirebaseUid(a) as LocalAccountScope.SignedIn
            val sb = LocalAccountScope.fromFirebaseUid(b) as LocalAccountScope.SignedIn
            val gate = AccountSdkRouteGate()
            val lease = requireNotNull(gate.reserve(sa))
            assertTrue(gate.activate(lease))
            val binding = FirebaseDevSdkBinding(named.app,named.auth,
                { gate.permits(lease,it) },primary.auth,named.db)
            assertEquals(a,binding.currentUid(sa))
            assertNull(binding.currentUid(sb))
            assertNull(FirebaseDevAccountSdkSessions.open(context,sa,named.app,
                AccountCacheTransitionEvidence(sa)))
            named.db.document("users/$a").set(userDoc()).awaitQa()
            other.db.document("users/$b").set(userDoc()).awaitQa()
            assertTrue(named.db.document("users/$a").get(Source.SERVER).awaitQa().exists())
            assertTrue(other.db.document("users/$b").get(Source.SERVER).awaitQa().exists())
            assertDenied { named.db.document("users/$b").get(Source.SERVER).awaitQa() }
            assertDenied { other.db.document("users/$a").get(Source.SERVER).awaitQa() }

            // This client has never cached B's document. A cache miss must not reveal it.
            try {
                assertFalse(named.db.document("users/$b").get(Source.CACHE).awaitQa().exists())
            } catch (failure: FirebaseFirestoreException) {
                assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE,failure.code)
            }

            primary.auth.signOut()
            assertNull(binding.currentUid(sa))
            assertDenied { primary.db.document("users/$a").get(Source.SERVER).awaitQa() }
            assertEquals(b,primary.signIn(subjectB))
            assertNull(binding.currentUid(sa))
            gate.retire(lease)
            assertFalse(gate.permitsLegacy(sa))
            assertEquals(0L,other.db.document("users/$b").get(Source.SERVER).awaitQa().getLong("revision"))
        } finally {
            named.finish()
            other.finish()
            primary.finish()
        }
    }

    @Test
    fun pendingWritesMustBeAcknowledgedBeforeMemorySessionClosesAndLegacyDataStays() = runBlocking {
        val context = fixtureContext()
        val primary = Fixture(context,"legacy-a",false)
        val named = Fixture(context,"memory-a",true)
        val other = Fixture(context,"legacy-b",false)
        var terminated = false
        try {
            val subject = "qa-" + UUID.randomUUID()
            val a = primary.signIn(subject)
            assertEquals(a,named.signIn(subject))
            val b = other.signIn("qa-" + UUID.randomUUID())
            primary.db.document("users/$a").set(userDoc()).awaitQa()
            other.db.document("users/$b").set(userDoc()).awaitQa()
            val bRef = other.db.document("users/$b")
            bRef.get(Source.SERVER).awaitQa()
            other.db.disableNetwork().awaitQa()
            val bWrite = bRef.update(mapOf("migrationState" to "paused","revision" to 1L))
            val bCached = bRef.get(Source.CACHE).awaitQa()
            assertTrue(bCached.metadata.hasPendingWrites())
            assertFalse(bWrite.isComplete)
            val target = named.db.document("users/$a")
            target.get(Source.SERVER).awaitQa()
            named.db.disableNetwork().awaitQa()
            val write = target.update(mapOf("migrationState" to "ready","revision" to 1L))
            val local = target.get(Source.CACHE).awaitQa()
            assertTrue(local.metadata.hasPendingWrites())
            assertFalse(write.isComplete)

            fun sameAccount() = primary.auth.currentUser?.uid == a && named.auth.currentUser?.uid == a
            val waiting = named.db.waitForPendingWrites()
            val unresolved = withTimeoutOrNull(300L) {
                drainAndCloseIsolatedSdk(
                    sameAccount = ::sameAccount,
                    drain = { true },
                    acknowledgeWrites = { waiting.awaitCompletion() },
                    stillDrained = { true },
                    terminate = { error("Must not terminate with a pending write") },
                    signOut = { error("Must not sign out with a pending write") },
                )
            }
            assertNull(unresolved)
            assertFalse(waiting.isComplete)
            assertTrue(sameAccount())

            named.db.enableNetwork().awaitQa()
            write.awaitQa()
            waiting.awaitQa()
            val acknowledged = target.get(Source.SERVER).awaitQa()
            assertEquals(1L,acknowledged.getLong("revision"))
            assertFalse(acknowledged.metadata.hasPendingWrites())
            val closed = withTimeout(20_000L) {
                drainAndCloseIsolatedSdk(
                    sameAccount = ::sameAccount,
                    drain = { true },
                    acknowledgeWrites = { named.db.waitForPendingWrites().awaitCompletion() },
                    stillDrained = { true },
                    terminate = { named.db.terminate().awaitCompletion(); terminated = true },
                    signOut = { named.auth.signOut(); named.auth.currentUser == null },
                )
            }
            assertTrue(closed)
            assertEquals(a,primary.auth.currentUser?.uid)
            assertEquals(1L,primary.db.document("users/$a").get(Source.SERVER).awaitQa().getLong("revision"))
            val preserved = bRef.get(Source.CACHE).awaitQa()
            assertEquals(bCached.data,preserved.data)
            assertTrue(preserved.metadata.hasPendingWrites())
            assertFalse(bWrite.isComplete)
            other.db.enableNetwork().awaitQa()
            bWrite.awaitQa()
            other.db.waitForPendingWrites().awaitQa()
            assertEquals(bCached.data,bRef.get(Source.SERVER).awaitQa().data)
            assertTrue(primary.db.firestoreSettings.cacheSettings is PersistentCacheSettings)
            assertTrue(other.db.firestoreSettings.cacheSettings is PersistentCacheSettings)
        } finally {
            named.finish(terminated)
            primary.finish()
            other.finish()
        }
    }

    private fun userDoc() = mapOf("schemaVersion" to 1L,"createdAtMs" to 1_700_000_000_000L,
        "migrationState" to "pending","revision" to 0L)

    private suspend fun assertDenied(action: suspend () -> Any?) {
        try {
            action()
            fail("Expected Firestore permission denial")
        } catch (failure: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,failure.code)
        }
    }
}

private suspend fun <T> Task<T>.awaitQa(): T = withTimeout(20_000L) { awaitCompletion() }
private suspend fun <T> Task<T>.awaitCompletion(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("SDK task failed"))
    }
}
