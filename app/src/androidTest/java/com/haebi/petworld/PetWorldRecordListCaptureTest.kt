package com.haebi.petworld

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.content.Context
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.toEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PetWorldRecordListCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val preferences = ThemePreferences(context)
    private val database = PetWorldDatabase.get(context)
    private val root = File(context.getExternalFilesDir(null), "record-list-qa")

    @Test fun captureRecordFiltersAndRowsInBothThemes() {
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { mkdirs() }
                ActivityScenario.launch(MainActivity::class.java).use {
                    seen("나비의 하루")
                    tap("기록")
                    seen("나비의 생활 기록")
                    seen("기간: 7일")
                    seen("유형: 전체")
                    seen("기록 6건")
                    seen("120mL")
                    capture(folder, "S11-record-list")

                    tap("기간: 7일")
                    seen("조회 기간")
                    capture(folder, "S13-range-picker")
                    tap("오늘")
                    seen("기간: 오늘")

                    tap("유형: 전체")
                    seen("기록 유형")
                    capture(folder, "S13-type-picker")
                    tap("음수")
                    seen("유형: 음수")
                    seen("기록 1건")
                    capture(folder, "S11-water-filtered")

                    tap("기간: 오늘")
                    tap("7일")
                    tap("유형: 음수")
                    tap("전체 유형")
                    seen("기록 6건")
                    capture(folder, "S11-filter-reset")
                }
                assertTrue(File(folder, "S11-record-list.png").isFile)
                assertTrue(File(folder, "S13-range-picker.png").isFile)
                assertTrue(File(folder, "S13-type-picker.png").isFile)
            }
        } finally {
            runBlocking {
                database.petDao().deleteAll()
                preferences.selectPet(null)
                preferences.setThemeMode(ThemeMode.SYSTEM)
            }
        }
    }

    private fun seed(mode: ThemeMode) = runBlocking {
        database.petDao().deleteAll()
        val now = System.currentTimeMillis()
        database.petDao().insert(PetEntity(
            id = "ui03-cat", name = "나비", species = "CAT", breed = "",
            birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
            neuterStatus = "UNKNOWN", notes = "", photoFile = null,
            isArchived = false, createdAt = now, updatedAt = now,
        ))
        preferences.selectPet("ui03-cat")
        preferences.setThemeMode(mode)
        val zone = ZoneId.systemDefault().id
        val samples = listOf(
            CareDraft(petId = "ui03-cat", type = CareType.FOOD,
                occurredAt = now - 120_000L, timezoneId = zone,
                amount = "40", consumedAmount = "30"),
            CareDraft(petId = "ui03-cat", type = CareType.WATER,
                occurredAt = now - 60_000L, timezoneId = zone, amount = "120"),
            CareDraft(petId = "ui03-cat", type = CareType.ELIMINATION,
                occurredAt = now - 180_000L, timezoneId = zone, amount = "1"),
            CareDraft(petId = "ui03-cat", type = CareType.ACTIVITY,
                occurredAt = now - 240_000L, timezoneId = zone, amount = "25"),
            CareDraft(petId = "ui03-cat", type = CareType.WEIGHT,
                occurredAt = now - 300_000L, timezoneId = zone, amount = "4.5"),
            CareDraft(petId = "ui03-cat", type = CareType.WEIGHT,
                occurredAt = now - 86_400_000L, timezoneId = zone, amount = "4.4"),
        )
        samples.forEach { database.careDao().insert(it.toEntity(now = now)) }
    }

    private fun seen(label: String) {
        val target = device.wait(Until.findObject(By.textContains(label)), 15_000)
        checkNotNull(target) { "화면에 필요한 텍스트가 없습니다: $label" }
    }

    private fun tap(label: String) {
        repeat(4) {
            val target = device.wait(Until.findObject(By.text(label)), 4_000)
            if (target != null) {
                try {
                    target.click()
                    device.waitForIdle()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    Thread.sleep(200)
                }
            }
        }
        error("UI-03 조작 실패: $label")
    }

    private fun capture(folder: File, name: String) {
        device.waitForIdle()
        Thread.sleep(350)
        val target = File(folder, "$name.png")
        check(device.takeScreenshot(target) && target.length() > 10_000) {
            "화면 PNG 저장 실패: $name"
        }
    }
}
