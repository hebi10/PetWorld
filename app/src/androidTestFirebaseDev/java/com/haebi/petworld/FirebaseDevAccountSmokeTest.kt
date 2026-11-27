package com.haebi.petworld

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.haebi.petworld.auth.PetWorldAccountActivity
import com.haebi.petworld.auth.FirebaseDevAccountMemoryCache
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.PersistentCacheSettings
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.google.android.gms.tasks.TaskCompletionSource
import com.haebi.petworld.auth.awaitTrackedFirebaseTask
import com.haebi.petworld.data.account.AccountCloudWorkTracker
import com.haebi.petworld.data.account.AccountSdkRouteJournal
import com.haebi.petworld.data.account.AccountCacheTransitionEvidence
import com.haebi.petworld.data.account.accountMemoryAppName
import com.haebi.petworld.auth.FirebaseDevAccountSdkSessions
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import java.util.UUID
import com.haebi.petworld.data.account.LocalAccountScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseDevAccountSmokeTest {
    @Test
    fun retainedRouteMarkerSurvivesNewJournalInstanceWithoutBlockingOtherAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = LocalAccountScope.fromFirebaseUid("route-qa-" + UUID.randomUUID()) as LocalAccountScope.SignedIn
        val b = LocalAccountScope.fromFirebaseUid("route-qa-other-" + UUID.randomUUID()) as LocalAccountScope.SignedIn
        val journal = AccountSdkRouteJournal(context)
        assertTrue(journal.permitsLegacy(a))
        assertTrue(journal.retain(a))
        assertTrue(journal.retain(a))
        val restarted = AccountSdkRouteJournal(context)
        assertFalse(restarted.permitsLegacy(a))
        assertTrue(restarted.permitsLegacy(b))
        assertTrue(restarted.retain(b))
        assertFalse(restarted.permitsLegacy(b))
    }

    @Test
    fun namedSdkSettingsDoNotChangeDefaultAndUnreviewedSessionCannotOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = LocalAccountScope.fromFirebaseUid("named-qa-" + UUID.randomUUID()) as LocalAccountScope.SignedIn
        val primary = FirebaseApp.getInstance()
        val named = FirebaseApp.initializeApp(context, primary.options, accountMemoryAppName(account))
        try {
            val defaultClient = FirebaseFirestore.getInstance(primary)
            val originalSettings = defaultClient.firestoreSettings
            val client = FirebaseFirestore.getInstance(named)
            client.firestoreSettings = FirebaseDevAccountMemoryCache.settings(client.firestoreSettings)
            assertNotSame(defaultClient,client)
            assertEquals(named.name,client.app.name)
            assertTrue(client.firestoreSettings.cacheSettings is MemoryCacheSettings)
            assertEquals(originalSettings,defaultClient.firestoreSettings)
            assertNull(FirebaseDevAccountSdkSessions.open(
                context,account,named,AccountCacheTransitionEvidence(account)))
            assertTrue(AccountSdkRouteJournal(context).permitsLegacy(account))
        } finally {
            // Do not delete a named FirebaseApp while Auth internal callbacks can
            // still resolve it. This unused fixture lives only in the QA process.
            // No authenticated user or cloud data was created.
        }
    }


    @Test
    fun isolatedMemorySettingsDoNotModifyLegacyPersistentSettings() {
        val legacy = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        val isolated = FirebaseDevAccountMemoryCache.settings(legacy)
        assertTrue(legacy.cacheSettings is PersistentCacheSettings)
        assertTrue(isolated.cacheSettings is MemoryCacheSettings)
        assertEquals(legacy.host, isolated.host)
        assertEquals(legacy.isSslEnabled, isolated.isSslEnabled)
    }

    @Test
    fun sdkTaskCompletionNotCoroutineCancellationReleasesDrainPermit() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        val account = LocalAccountScope.fromFirebaseUid("synthetic-qa") as LocalAccountScope.SignedIn
        val source = TaskCompletionSource<String>()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitTrackedFirebaseTask<String>(account, tracker, permitted = { true }) { source.task }
        }
        assertEquals(1, tracker.snapshot(account).sdkTasks)
        tracker.blockNewWork(account)
        waiting.cancelAndJoin()
        assertEquals(1, tracker.snapshot(account).sdkTasks)
        assertFalse(tracker.awaitDrained(account, 20))
        source.setResult("synthetic-completion")
        assertTrue(tracker.awaitDrained(account, 1000))
        assertEquals(0, tracker.snapshot(account).sdkTasks)
    }

    @Test
    fun sdkTaskBlockedAccountNeverInvokesTaskFactory() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        val account = LocalAccountScope.fromFirebaseUid("blocked-qa") as LocalAccountScope.SignedIn
        tracker.blockNewWork(account)
        var starts = 0
        val result = runCatching {
            awaitTrackedFirebaseTask(account, tracker, permitted = { true }) {
                starts++
                TaskCompletionSource<String>().task
            }
        }
        assertTrue(result.isFailure)
        assertEquals(0, starts)
        assertTrue(tracker.snapshot(account).drained)
    }

    @Test
    fun accountScreenShowsGuestAndLocalOnlyStatusWithoutStartingGoogleLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val appPackage = instrumentation.targetContext.packageName
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")

        val preferences = ThemePreferences(instrumentation.targetContext)
        val oldTheme = runBlocking { preferences.themeMode.first() }
        runBlocking { preferences.setThemeMode(ThemeMode.LIGHT) }
        try {
            ActivityScenario.launch(PetWorldAccountActivity::class.java).use { scenario ->
                // Recheck the Android window after Activity launch; boot overlays can
                // appear between the first keyguard dismissal and the resumed Activity.
                device.executeShellCommand("wm dismiss-keyguard")
                device.executeShellCommand("cmd statusbar collapse")
                device.waitForIdle(5_000)
                val initialTitle = By.pkg(appPackage).text("계정")
                if (!device.wait(Until.hasObject(initialTitle), 10_000) &&
                    device.currentPackageName in setOf("android", "com.android.systemui") &&
                    scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED) {
                    device.executeShellCommand("wm dismiss-keyguard")
                    device.executeShellCommand("cmd statusbar collapse")
                    scenario.recreate()
                    device.waitForIdle(5_000)
                }

                fun assertVisible(label: String, timeout: Long = 10_000) {
                    val visible = device.wait(Until.hasObject(By.pkg(appPackage).text(label)), timeout)
                    assertTrue(
                        "$label 표시 필요 (foreground=" + device.currentPackageName +
                            ", screenOn=" + device.isScreenOn + ", lifecycle=" + scenario.state +
                            ", focus=" + device.executeShellCommand("dumpsys window | grep mCurrentFocus").take(300) + ")",
                        visible,
                    )
                }

                assertVisible("계정", 30_000)
                assertVisible("계정 상태")
                assertVisible("로그인하지 않음")
                val screenshotRoot = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
                val screenshotFolder = java.io.File(screenshotRoot, "audit-d-account").apply { mkdirs() }
                fun captureTheme(light: Boolean, fileName: String) {
                    var matches = false
                    var systemBars = Insets.NONE
                    for (attempt in 1..40) {
                        scenario.onActivity { activity ->
                            val decor = activity.window.decorView
                            val controller = WindowCompat.getInsetsController(activity.window, decor)
                            matches = controller.isAppearanceLightStatusBars == light &&
                                controller.isAppearanceLightNavigationBars == light
                            systemBars = ViewCompat.getRootWindowInsets(decor)
                                ?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: Insets.NONE
                        }
                        if (matches && systemBars.top > 1 && systemBars.bottom > 1) break
                        Thread.sleep(100)
                    }
                    assertTrue("계정 시스템 바 명도 불일치: light=$light", matches)
                    assertTrue(
                        "계정 화면의 상단·하단 시스템 바를 확인할 수 없습니다",
                        systemBars.top > 1 && systemBars.bottom > 1,
                    )
                    device.waitForIdle()
                    val png = java.io.File(screenshotFolder, "$fileName.png")
                    assertTrue("비회원 계정 화면 캡처 실패", device.takeScreenshot(png) && png.length() > 10_000)
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(png.path))
                    try {
                        val x = bitmap.width / 3
                        val top = ColorUtils.calculateLuminance(bitmap.getPixel(x, systemBars.top / 2))
                        val bottom = ColorUtils.calculateLuminance(
                            bitmap.getPixel(x, bitmap.height - systemBars.bottom / 2),
                        )
                        val backgroundRange = if (light) 0.7..1.0 else 0.0..0.15
                        assertTrue(
                            "계정 시스템 바 배경 명도 불일치: light=$light, top=$top, bottom=$bottom",
                            top in backgroundRange && bottom in backgroundRange,
                        )
                    } finally {
                        bitmap.recycle()
                    }
                    device.dumpWindowHierarchy(java.io.File(screenshotFolder, "$fileName.xml"))
                }
                captureTheme(true, "D23-guest-account")
                assertFalse("비회원에게 계정 삭제 진입 버튼이 표시되면 안 됩니다",
                    device.hasObject(By.pkg(appPackage).text("계정 삭제")))

                fun assertVisibleByScrolling(label: String) {
                    if (!device.hasObject(By.pkg(appPackage).text(label))) {
                        UiScrollable(UiSelector().packageName(appPackage).scrollable(true))
                            .setMaxSearchSwipes(6)
                            .scrollIntoView(UiSelector().packageName(appPackage).text(label))
                    }
                    assertVisible(label)
                }
                assertVisibleByScrolling("이 기기의 기록")
                assertVisibleByScrolling("Google 계정으로 시작")
                assertVisibleByScrolling("삭제 처리 상태 확인")
                assertVisibleByScrolling("기기 인증 후 상태 확인")
                assertFalse("명시적인 기기 인증 전 저장 상태를 표시하면 안 됩니다",
                    device.hasObject(By.pkg(appPackage).text("기기에 저장된 마지막 확인 상태")))
                assertVisibleByScrolling("로그인 없이 사용")
                assertFalse("비회원에게 계정 삭제 진입 버튼이 표시되면 안 됩니다",
                    device.hasObject(By.pkg(appPackage).text("계정 삭제")))

                runBlocking { preferences.setThemeMode(ThemeMode.DARK) }
                scenario.recreate()
                device.executeShellCommand("wm dismiss-keyguard")
                device.executeShellCommand("cmd statusbar collapse")
                assertVisible("계정", 30_000)
                assertVisible("계정 상태")
                assertVisible("로그인하지 않음")
                captureTheme(false, "D23-guest-account-dark")
            }
        } finally {
            runBlocking { preferences.setThemeMode(oldTheme) }
        }
    }
}
