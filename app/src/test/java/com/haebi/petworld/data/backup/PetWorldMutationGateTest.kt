package com.haebi.petworld.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PetWorldMutationGateTest {
    @Test fun settingWriteCannotProceedWhileRestoreOwnsGate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = ThemePreferences(context)
        preferences.setThemeMode(ThemeMode.SYSTEM)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            PetWorldMutationGate.mutex.withLock {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            val writer = async { preferences.setThemeMode(ThemeMode.DARK) }
            yield()
            assertFalse(writer.isCompleted)
            assertEquals(ThemeMode.SYSTEM, preferences.themeMode.first())
            release.complete(Unit)
            writer.await()
            assertEquals(ThemeMode.DARK, preferences.themeMode.first())
        } finally {
            release.complete(Unit)
            owner.join()
            preferences.setThemeMode(ThemeMode.SYSTEM)
        }
    }
}
