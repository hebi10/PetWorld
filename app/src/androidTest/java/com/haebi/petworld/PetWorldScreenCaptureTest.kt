package com.haebi.petworld

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.data.toEntity
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID

/**
 * Source-faithful screenshots taken from the actual Activity and Android emulator.
 * Test fixture is isolated to the emulator and uses no mock screenshots.
 * Artifacts remain under the app external-files directory until pulled by CI.
 */
@RunWith(AndroidJUnit4::class)
class PetWorldScreenCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val preferences = ThemePreferences(context)
    private val database = PetWorldDatabase.get(context)
    private val root = File(context.getExternalFilesDir(null), "ui-captures")

    @Test fun captureImplementedScreensLightAndDark() {
        root.deleteRecursively()
        root.mkdirs()
        runBlocking {
            database.petDao().deleteAll()
            preferences.selectPet(null)
            preferences.setThemeMode(ThemeMode.LIGHT)
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val folder = File(root, theme.name.lowercase()).apply { mkdirs() }
            runBlocking {
                preferences.setThemeMode(theme)
                database.petDao().deleteAll()
                preferences.selectPet(null)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                dismissLauncherAnr()
                device.waitForIdle()
                shot(folder, "S00-onboarding")
                try {
                    mustSee("반려동물 등록")
                    assertSystemBarState(theme, it)
                } catch (error: Exception) {
                    device.dumpWindowHierarchy(File(folder, "S00-window-hierarchy.xml"))
                    throw error
                }
                tap("반려동물 등록")
                mustSee("이름 (필수)")
                shot(folder, "S02-profile-create")
                tap("닫기")
            }
            seed()
            ActivityScenario.launch(MainActivity::class.java).use {
                mustSee("나비의 하루")
                assertSystemBarState(theme, it)
                shot(folder, "S03-home")
                if (ensureVisible("최근 기록")) {
                    scrollToTop()
                    mustSee("나비의 하루")
                }
                openPetSwitcher(folder, "S03-home")
                mustSee("반려동물 선택")
                shot(folder, "S04-pet-switcher")
                device.pressBack()

                tap("빠른 기록 추가")
                mustSee("나비의 기록 추가")
                shot(folder, "S05-quick-record-sheet")
                device.pressBack()

                val forms = listOf(
                    "식사" to "S06-food-form",
                    "음수" to "S07-water-form",
                    "배변" to "S08-elimination-form",
                    "활동" to "S09-activity-form",
                    "체중" to "S10-weight-form",
                )
                forms.forEach { (label, name) ->
                    tap("빠른 기록 추가")
                    tap(label)
                    mustSee("닫기")
                    shot(folder, name)
                    tap("닫기")
                    mustSee("빠른 기록 추가")
                }

                tap("기록")
                mustSee("나비의 생활 기록")
                mustSee("기간: 7일")
                mustSee("유형: 전체")
                shot(folder, "S11-record-list")
                tap("기간: 7일")
                mustSee("조회 기간")
                shot(folder, "S13-range-picker")
                tap("오늘")
                mustSee("기간: 오늘")
                tap("유형: 전체")
                mustSee("기록 유형")
                shot(folder, "S13-type-picker")
                tap("음수")
                mustSee("유형: 음수")
                tap("기간: 오늘")
                tap("7일")
                tap("유형: 음수")
                tap("전체 유형")
                mustSee("기록 6건")
                val row = device.wait(Until.findObject(By.text("120mL")), 3000)
                checkNotNull(row) { "음수 기록 카드를 찾지 못했습니다." }.click()
                mustSee("기록 삭제")
                shot(folder, "S12-record-detail")
                tap("수정")
                shot(folder, "S12-record-edit")
                tap("닫기")
                tap("기록 삭제")
                mustSee("이 기록을 삭제할까요?")
                shot(folder, "S33-record-delete-dialog")
                tap("취소")
                tap("목록으로")

                tap("건강")
                mustSee("나비의 체중·건강")
                shot(folder, "S18-health")
                tap("통계")
                mustSee("나비의")
                shot(folder, "S14-statistics")
                tap("더보기")
                mustSee("데이터 관리")
                shot(folder, "S30-settings")
                tap("암호화 백업 생성")
                mustSee("백업 암호 설정")
                shot(folder, "S31-backup-password-dialog")
                tap("취소")

                tap("백업 파일 복원")
                device.waitForIdle()
                shot(folder, "S31-restore-file-picker")
                device.pressBack()

                tap("홈")
                tap("프로필 수정")
                mustSee("프로필 수정")
                shot(folder, "S02-profile-edit")
                tap("닫기")
                openPetSwitcher(folder, "S02-profile-edit")
                tap("전체 프로필 관리")
                mustSee("프로필 관리")
                shot(folder, "S16-profile-manager")
                tap("영구 삭제")
                mustSee("프로필을 삭제하시겠습니까?")
                shot(folder, "S33-profile-delete-dialog")
                tap("취소")
            }
        }
        runBlocking {
            database.petDao().deleteAll()
            preferences.selectPet(null)
            preferences.setThemeMode(ThemeMode.SYSTEM)
        }
        assertTrue(File(root, "light/S03-home.png").isFile)
        assertTrue(File(root, "dark/S30-settings.png").isFile)
    }

    private fun seed() = runBlocking {
        database.petDao().deleteAll()
        val now = System.currentTimeMillis()
        val pets = listOf("나비" to "cat", "초코" to "dog")
        pets.forEach { (name, id) ->
            database.petDao().insert(PetEntity(
                id = id, name = name, species = if (id == "cat") "CAT" else "DOG",
                breed = "", birthday = null, estimatedAgeYears = null,
                sex = "UNKNOWN", neuterStatus = "UNKNOWN",
                notes = if (id == "cat") "건강 상태를 관찰하고 있습니다." else "",
                photoFile = null, isArchived = false, createdAt = now, updatedAt = now,
            ))
        }
        preferences.selectPet("cat")
        val zone = ZoneId.systemDefault().id
        val records = listOf(
            CareDraft(petId = "cat", type = CareType.FOOD,
                occurredAt = now - 120_000, timezoneId = zone,
                amount = "40", consumedAmount = "30"),
            CareDraft(petId = "cat", type = CareType.WATER,
                occurredAt = now - 60_000, timezoneId = zone, amount = "120"),
            CareDraft(petId = "cat", type = CareType.ELIMINATION,
                occurredAt = now - 180_000, timezoneId = zone, amount = "1"),
            CareDraft(petId = "cat", type = CareType.ACTIVITY,
                occurredAt = now - 240_000, timezoneId = zone, amount = "25"),
            CareDraft(petId = "cat", type = CareType.WEIGHT,
                occurredAt = now - 300_000, timezoneId = zone, amount = "4.5"),
            CareDraft(petId = "cat", type = CareType.WEIGHT,
                occurredAt = now - 86_400_000, timezoneId = zone, amount = "4.4"),
        )
        records.forEach { database.careDao().insert(it.toEntity(now = now)) }
    }

    private fun assertSystemBarState(theme: ThemeMode, scenario: ActivityScenario<MainActivity>) {
        val brand = device.wait(Until.findObject(By.text("PetWorld")), 8_000)
        checkNotNull(brand) { "브랜드 헤더를 찾지 못했습니다." }
        var statusBarInset = 0
        // The user-selected app theme, not only the OS theme, must determine icon contrast.
        scenario.onActivity { activity ->
            val decorView = activity.window.decorView
            statusBarInset = ViewCompat.getRootWindowInsets(decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            val controller = WindowCompat.getInsetsController(activity.window, decorView)
            assertEquals(theme == ThemeMode.LIGHT, controller.isAppearanceLightStatusBars)
            assertEquals(theme == ThemeMode.LIGHT, controller.isAppearanceLightNavigationBars)
        }
        check(statusBarInset > 0) { "실제 상태 표시줄 인셋을 읽지 못했습니다." }
        check(brand.visibleBounds.top >= statusBarInset - 2) {
            "브랜드와 실제 상태 표시줄 영역이 겹칩니다: brand=${brand.visibleBounds.top}, inset=$statusBarInset"
        }
    }

    private fun dismissLauncherAnr() {
        repeat(3) {
            val waitButton = device.wait(Until.findObject(By.text("Wait")), 700)
            if (waitButton != null && device.hasObject(By.textContains("Pixel Launcher"))) {
                waitButton.click()
                device.waitForIdle()
            }
        }
    }

    private fun ensureVisible(text: String): Boolean {
        dismissLauncherAnr()
        if (device.wait(Until.findObject(By.textContains(text)), 700) != null) {
            return false
        }
        val middle = device.displayWidth / 2
        val high = device.displayHeight * 3 / 4
        val low = device.displayHeight / 4
        repeat(8) {
            device.swipe(middle, high, middle, low, 16)
            device.waitForIdle()
            if (device.wait(Until.findObject(By.textContains(text)), 700) != null) {
                return true
            }
        }
        throw IllegalStateException("스크롤 후에도 화면 텍스트가 없습니다: $text")
    }

    private fun scrollToTop() {
        val middle = device.displayWidth / 2
        val high = device.displayHeight * 3 / 4
        val low = device.displayHeight / 4
        repeat(8) {
            device.swipe(middle, low, middle, high, 16)
            device.waitForIdle()
        }
    }

    private fun openPetSwitcher(folder: File, stage: String) {
        dismissLauncherAnr()
        device.waitForIdle()
        val switcher = device.wait(
            Until.findObject(By.descContains("반려동물 전환")),
            5_000,
        )
        if (switcher == null) {
            device.dumpWindowHierarchy(File(folder, "$stage-window-hierarchy.xml"))
        }
        checkNotNull(switcher) { "프로필 전환 접근성 요소를 찾지 못했습니다." }.click()
        device.waitForIdle()
    }

    private fun mustSee(text: String) {
        dismissLauncherAnr()
        val found = device.wait(Until.findObject(By.textContains(text)), 25_000)
        checkNotNull(found) { "화면 텍스트가 없습니다: $text" }
        device.waitForIdle()
    }

    private fun tap(text: String) {
        var lastError: StaleObjectException? = null
        for (attempt in 0 until 12) {
            dismissLauncherAnr()
            device.waitForIdle()
            val found = device.wait(Until.findObject(By.text(text)), 1_500)
            if (found != null) {
                try {
                    found.click()
                    device.waitForIdle()
                    return
                } catch (error: StaleObjectException) {
                    lastError = error
                    Thread.sleep(200)
                }
            }
            val middle = device.displayWidth / 2
            val high = device.displayHeight * 3 / 4
            val low = device.displayHeight / 4
            if (attempt < 6) {
                device.swipe(middle, high, middle, low, 16)
            } else {
                device.swipe(middle, low, middle, high, 16)
            }
        }
        throw IllegalStateException("버튼/항목을 찾지 못했습니다: $text", lastError)
    }

    private fun shot(folder: File, id: String) {
        device.waitForIdle()
        Thread.sleep(450)
        val file = File(folder, "$id.png")
        check(device.takeScreenshot(file)) { "화면 캡처에 실패했습니다: $id" }
        check(file.length() > 10_000L) { "비어 있는 화면 캡처입니다: $id" }
    }
}
