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
import com.haebi.petworld.ui.theme.PetSpacing
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
                dismissSystemAnr()
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
                tap("닫기")
                check(device.wait(Until.gone(By.text("나비의 기록 추가")), 10_000)) {
                    "빠른 기록 시트가 닫히지 않았습니다."
                }

                val forms = listOf(
                    "식사" to "S06-food-form",
                    "음수" to "S07-water-form",
                    "배변" to "S08-elimination-form",
                    "활동" to "S09-activity-form",
                    "체중" to "S10-weight-form",
                )
                forms.forEach { (label, name) ->
                    tap("빠른 기록 추가")
                    mustSee("나비의 기록 추가")
                    openCareForm(label)
                    mustSee("닫기")
                    shot(folder, name)
                    assertCareFormMaxWidth(folder, name, label)
                    if (label == "식사") assertFoodPortionLayout(folder)
                    if (label == "배변") assertEliminationPhotoLayout(folder)
                    assertCareDateTimeRow(folder, name)
                    tap("닫기")
                    mustSee("빠른 기록 추가")
                }

                openRecordsTab()
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
                assertCareFormMaxWidth(folder, "S12-record-edit", "음수", edit = true)
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
                waitForDocumentPicker(folder)
                shot(folder, "S31-restore-file-picker")
                device.pressBack()
                dismissSystemAnr()
                mustSee("설정")

                tap("홈")
                tap("수정")
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

    private fun dismissSystemAnr() {
        val anrTitle = device.wait(Until.findObject(By.textContains("isn't responding")), 700)
        val title = try { anrTitle?.text.orEmpty() } catch (_: StaleObjectException) { "" }
        val waitButton = device.findObject(By.text("Wait"))
        val closeButton = device.findObject(By.text("Close app"))
        if (anrTitle == null && waitButton == null && closeButton == null) return

        if (title.contains("Pixel Launcher", ignoreCase = true)) {
            try { closeButton?.click() } catch (_: StaleObjectException) {}
            device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
            device.waitForIdle()
            return
        }

        if (waitButton != null) {
            try { waitButton.click() } catch (_: StaleObjectException) {}
            device.waitForIdle()
        }
        if (device.hasObject(By.textContains("isn't responding")) || device.hasObject(By.text("Close app"))) {
            val close = device.findObject(By.text("Close app"))
            if (close != null) {
                try { close.click() } catch (_: StaleObjectException) {}
            } else {
                when {
                    title.contains("googlesdksetup", ignoreCase = true) ->
                        device.executeShellCommand("am force-stop com.google.android.googlesdksetup")
                    title.contains("Pixel Launcher", ignoreCase = true) ->
                        device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
                }
            }
            device.waitForIdle()
        }
    }

    private fun waitForDocumentPicker(folder: File) {
        repeat(6) {
            dismissSystemAnr()
            val picker = device.wait(
                Until.findObject(By.pkg("com.google.android.documentsui")),
                1_500,
            )
            if (picker != null) {
                device.waitForIdle()
                return
            }
            Thread.sleep(250)
        }
        device.dumpWindowHierarchy(File(folder, "S31-restore-file-picker-window-hierarchy.xml"))
        throw IllegalStateException("시스템 파일 선택기(DocumentsUI)가 열리지 않았습니다.")
    }

    private fun openRecordsTab() {
        repeat(3) {
            dismissSystemAnr()
            if (device.wait(Until.findObject(By.textContains("나비의 생활 기록")), 1_000) != null) {
                return
            }
            tap("기록")
        }
        mustSee("나비의 생활 기록")
    }

    private fun ensureVisible(text: String): Boolean {
        dismissSystemAnr()
        if (device.wait(Until.findObject(By.textContains(text)), 700) != null) {
            return false
        }
        val middle = device.displayWidth / 2
        val high = device.displayHeight * 3 / 4
        val low = device.displayHeight / 4
        for (scrollDown in listOf(true, false)) {
            repeat(14) {
                dismissSystemAnr()
                device.swipe(
                    middle,
                    if (scrollDown) high else low,
                    middle,
                    if (scrollDown) low else high,
                    22,
                )
                device.waitForIdle()
                if (device.wait(Until.findObject(By.textContains(text)), 700) != null) {
                    return true
                }
            }
        }
        val diagnostics = File(root, "diagnostics").apply { mkdirs() }
        val id = System.currentTimeMillis().toString()
        device.takeScreenshot(File(diagnostics, "$id-recent-content.png"))
        device.dumpWindowHierarchy(File(diagnostics, "$id-recent-content.xml"))
        throw IllegalStateException("스크롤 후에도 화면 텍스트가 없습니다: $text (diagnostics/$id)")
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

    private fun assertCareFormMaxWidth(
        folder: File,
        screenId: String,
        type: String,
        edit: Boolean = false,
    ) {
        val saveText = if (edit) "변경 저장" else "기록 저장"
        val labelText = when (type) {
            "식사" -> "제품명 (선택)"
            "음수" -> "음수량 (mL) · 필수"
            "배변" -> "횟수 (회)"
            "활동" -> "지속시간 (분)"
            "체중" -> "체중 (kg)"
            else -> error("지원하지 않는 기록 유형: $type")
        }
        val save = checkNotNull(device.wait(Until.findObject(By.text(saveText)), 4_000)) {
            "$screenId: 하단 저장 버튼을 찾지 못했습니다."
        }
        val field = checkNotNull(device.wait(Until.findObject(By.textContains(labelText)), 4_000)) {
            "$screenId: 대표 입력 필드를 찾지 못했습니다."
        }
        val density = context.resources.displayMetrics.density
        val screenWidthDp = device.displayWidth / density
        val buttonBounds = save.visibleBounds
        val fieldBounds = field.visibleBounds
        val screenCenter = device.displayWidth / 2f

        if (screenWidthDp > 600f) {
            val maxWidth = PetSpacing.careFormMaxWidth.value * density
            val maxSaveWidth = (PetSpacing.careFormMaxWidth.value -
                2f * PetSpacing.large.value + 8f) * density
            val tolerance = 16f * density
            val centered = kotlin.math.abs(
                (buttonBounds.left + buttonBounds.right) / 2f - screenCenter
            ) <= tolerance
            check(buttonBounds.width() <= maxSaveWidth &&
                centered &&
                fieldBounds.left >= screenCenter - maxWidth / 2f - tolerance &&
                fieldBounds.right <= screenCenter + maxWidth / 2f + tolerance) {
                "$screenId: 펼침 화면에서 폼 또는 저장 버튼이 520dp 최대폭을 초과했습니다. " +
                    "field=$fieldBounds, button=$buttonBounds, deviceWidth=${device.displayWidth}"
            }
        } else {
            val minimumButtonWidth = (screenWidthDp - 64f) * density
            check(buttonBounds.width() >= minimumButtonWidth) {
                "$screenId: 일반 휴대전화의 저장 버튼 폭이 줄어들었습니다. " +
                    "button=$buttonBounds, screenWidthDp=$screenWidthDp"
            }
        }
        shot(folder, "UI09-$screenId-form-width")
    }

    private fun assertEliminationPhotoLayout(folder: File) {
        ensureVisible("사진 선택")
        val preview = device.wait(Until.findObject(By.desc("관찰 사진 없음")), 2_000)
        val state = device.wait(Until.findObject(By.text("사진 없음")), 2_000)
        val button = device.wait(Until.findObject(By.text("사진 선택")), 2_000)
        if (preview == null || state == null || button == null) {
            device.takeScreenshot(File(folder, "UI06D-elimination-photo-failure.png"))
            device.dumpWindowHierarchy(File(folder, "UI06D-elimination-photo-hierarchy.xml"))
        }
        val thumbnailBounds = checkNotNull(preview) { "배변 관찰 사진 미리보기가 없습니다." }.visibleBounds
        val buttonBounds = checkNotNull(button) { "사진 선택 버튼을 찾지 못했습니다." }.visibleBounds
        checkNotNull(state) { "사진 없음 상태 설명을 찾지 못했습니다." }
        val maxThumbSize = (context.resources.displayMetrics.density * 72).toInt()
        check(thumbnailBounds.width() in 1..maxThumbSize &&
            thumbnailBounds.height() in 1..maxThumbSize &&
            thumbnailBounds.right < buttonBounds.left &&
            thumbnailBounds.top < buttonBounds.bottom &&
            buttonBounds.top < thumbnailBounds.bottom) {
            "배변 사진 썸네일과 선택 버튼이 가로로 정렬되지 않았습니다."
        }
        shot(folder, "UI06D-elimination-photo")
        scrollToTop()
    }

    private fun assertFoodPortionLayout(folder: File) {
        val servedSelector = By.textContains("제공량 (")
        val consumedSelector = By.textContains("섭취량 (")
        val centerX = device.displayWidth / 2
        val high = device.displayHeight * 3 / 4
        val low = device.displayHeight / 4
        var bounds: Pair<android.graphics.Rect, android.graphics.Rect>? = null

        for (attempt in 0 until 12) {
            dismissSystemAnr()
            bounds = try {
                val served = device.findObject(servedSelector)?.visibleBounds
                val consumed = device.findObject(consumedSelector)?.visibleBounds
                if (served != null && consumed != null && served.width() > 0 &&
                    consumed.width() > 0 && served.height() > 0 && consumed.height() > 0
                ) served to consumed else null
            } catch (_: StaleObjectException) {
                null
            }
            if (bounds != null) break
            device.swipe(centerX, high, centerX, low, 22)
            device.waitForIdle()
        }

        if (bounds == null) {
            device.takeScreenshot(File(folder, "UI06C-food-portions-failure.png"))
            device.dumpWindowHierarchy(File(folder, "UI06C-food-portions-hierarchy.xml"))
        }
        val (served, consumed) = checkNotNull(bounds) {
            "식사 제공량과 섭취량 필드를 동시에 찾지 못했습니다."
        }

        val screenWidthDp = device.displayWidth / context.resources.displayMetrics.density
        val stackExpected = screenWidthDp - 32f < 360f ||
            context.resources.configuration.fontScale >= 1.3f
        val verticallyStacked = served.bottom <= consumed.top
        val horizontallyAligned = served.right <= consumed.left &&
            served.top < consumed.bottom && consumed.top < served.bottom
        check(if (stackExpected) verticallyStacked else horizontallyAligned) {
            "식사 수치 필드의 반응형 배치가 올바르지 않습니다. stacked=$stackExpected"
        }
        shot(folder, "UI06C-food-portions")
        scrollToTop()
    }

    private fun assertCareDateTimeRow(folder: File, screenId: String) {
        val dateSelector = By.descContains("관찰 날짜")
        val timeSelector = By.descContains("관찰 시간")
        val middle = device.displayWidth / 2
        val high = device.displayHeight * 3 / 4
        val low = device.displayHeight / 4
        val safeBottom = (device.displayHeight * 0.86f).toInt()
        val minTouchHeight = (context.resources.displayMetrics.density * 48).toInt()
        var rowVerified = false

        for (attempt in 0 until 12) {
            dismissSystemAnr()
            val bounds = try {
                val date = device.findObject(dateSelector)?.visibleBounds
                val time = device.findObject(timeSelector)?.visibleBounds
                if (date != null && time != null) date to time else null
            } catch (_: StaleObjectException) {
                null
            }
            if (bounds != null) {
                val (date, time) = bounds
                val horizontallyAligned = date.right <= time.left &&
                    date.top < time.bottom && time.top < date.bottom
                if (horizontallyAligned && date.bottom <= safeBottom &&
                    time.bottom <= safeBottom && date.height() >= minTouchHeight &&
                    time.height() >= minTouchHeight) {
                    rowVerified = true
                    break
                }
            }
            device.swipe(middle, high, middle, low, 22)
            device.waitForIdle()
        }

        if (!rowVerified) {
            device.takeScreenshot(File(folder, "$screenId-observation-failure.png"))
            device.dumpWindowHierarchy(File(folder, "$screenId-observation-hierarchy.xml"))
        }
        check(rowVerified) {
            "$screenId: 날짜·시간 버튼이 가로로 정렬되고 화면 안에 완전히 표시되어야 합니다."
        }
        shot(folder, "UI06B-$screenId-observation")
        scrollToTop()
    }

    private fun openPetSwitcher(folder: File, stage: String) {
        dismissSystemAnr()
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

    private fun openCareForm(label: String) {
        val title = "나비 · $label 기록"
        repeat(2) {
            tap(label)
            repeat(8) {
                dismissSystemAnr()
                if (device.wait(Until.findObject(By.textContains(title)), 1_500) != null) {
                    device.waitForIdle()
                    return
                }
            }
            if (!device.hasObject(By.textContains("나비의 기록 추가"))) {
                mustSee(title)
                return
            }
        }
        mustSee(title)
    }

    private fun mustSee(text: String) {
        repeat(12) {
            dismissSystemAnr()
            if (device.wait(Until.findObject(By.textContains(text)), 2_000) != null) {
                device.waitForIdle()
                return
            }
        }
        val diagnostics = File(root, "diagnostics").apply { mkdirs() }
        val id = System.currentTimeMillis().toString()
        device.takeScreenshot(File(diagnostics, "$id.png"))
        device.dumpWindowHierarchy(File(diagnostics, "$id.xml"))
        throw IllegalStateException("화면 텍스트가 없습니다: $text (diagnostics/$id)")
    }

    private fun tap(text: String) {
        var lastError: StaleObjectException? = null
        for (attempt in 0 until 12) {
            dismissSystemAnr()
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
        dismissSystemAnr()
        device.waitForIdle()
        Thread.sleep(450)
        val file = File(folder, "$id.png")
        check(device.takeScreenshot(file)) { "화면 캡처에 실패했습니다: $id" }
        check(file.length() > 10_000L) { "비어 있는 화면 캡처입니다: $id" }
    }
}
