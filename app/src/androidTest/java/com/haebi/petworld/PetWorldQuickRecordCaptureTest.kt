package com.haebi.petworld

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PetWorldQuickRecordCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database = PetWorldDatabase.get(context)
    private val preferences = ThemePreferences(context)
    private val root = File(context.getExternalFilesDir(null), "ui05-captures")

    @Test fun quickRecordTwoColumnOptionsAndNavigation() {
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { mkdirs() }
                ActivityScenario.launch(MainActivity::class.java).use {
                    seen("나비의 하루")
                    tap("빠른 기록 추가")
                    seen("나비의 기록 추가")
                    listOf("식사", "음수", "배변", "활동", "체중").forEach(::seen)
                    seen("닫기")
                    capture(folder, "S05-quick-record-sheet")

                    tap("식사")
                    seen("나비 · 식사 기록")
                    capture(folder, "S06-food-from-quick")
                    tap("닫기")
                    seen("나비의 하루")

                    tap("빠른 기록 추가")
                    tap("체중")
                    seen("나비 · 체중 기록")
                    capture(folder, "S10-weight-from-quick")
                    tap("닫기")
                    seen("나비의 하루")

                    tap("빠른 기록 추가")
                    tap("닫기")
                    seen("빠른 기록 추가")
                }
                assertTrue(File(folder, "S05-quick-record-sheet.png").isFile)
                assertTrue(File(folder, "S06-food-from-quick.png").isFile)
                assertTrue(File(folder, "S10-weight-from-quick.png").isFile)
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
        database.petDao().insert(
            PetEntity(
                id = "ui05-cat", name = "나비", species = "CAT", breed = "",
                birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
                neuterStatus = "UNKNOWN", notes = "", photoFile = null,
                isArchived = false, createdAt = now, updatedAt = now,
            )
        )
        preferences.selectPet("ui05-cat")
        preferences.setThemeMode(mode)
    }

    private fun dismissSystemDialog() {
        repeat(2) {
            val wait = device.wait(Until.findObject(By.text("Wait")), 500)
            if (wait != null && device.hasObject(By.textContains("Pixel Launcher"))) {
                wait.click()
                device.waitForIdle()
            }
        }
    }

    private fun seen(text: String) {
        dismissSystemDialog()
        checkNotNull(device.wait(Until.findObject(By.textContains(text)), 12_000)) {
            "UI-05 화면 텍스트가 없습니다: $text"
        }
    }

    private fun tap(text: String) {
        repeat(5) {
            dismissSystemDialog()
            val target = device.wait(Until.findObject(By.text(text)), 2_000)
            if (target != null) {
                try {
                    target.click()
                    device.waitForIdle()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    Thread.sleep(250)
                }
            }
        }
        error("UI-05 버튼 클릭 실패: $text")
    }

    private fun capture(folder: File, name: String) {
        device.waitForIdle()
        Thread.sleep(400)
        val file = File(folder, "$name.png")
        check(device.takeScreenshot(file) && file.length() > 10_000) {
            "UI-05 화면 PNG 저장 실패: $name"
        }
    }
}
