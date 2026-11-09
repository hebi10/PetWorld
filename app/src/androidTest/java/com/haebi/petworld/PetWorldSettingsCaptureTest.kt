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
class PetWorldSettingsCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database = PetWorldDatabase.get(context)
    private val preferences = ThemePreferences(context)
    private val root = File(context.getExternalFilesDir(null), "ui04-captures")

    @Test fun compactSettingsAndProfileManagerRemainUsable() {
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { mkdirs() }
                ActivityScenario.launch(MainActivity::class.java).use {
                    seen("나비의 하루")
                    tap("더보기")
                    seen("데이터 관리")
                    seen("등록 2개")
                    seen("프로필 관리")
                    seen("반려동물 추가")
                    seen("암호화 백업 생성")
                    seen("백업 파일 복원")
                    capture(folder, "S30-settings-compact")

                    tap("프로필 관리")
                    seen("나비")
                    seen("초코")
                    seen("프로필 보관")
                    capture(folder, "S16-manager-compact")

                    tap("영구 삭제")
                    seen("프로필을 삭제하시겠습니까?")
                    capture(folder, "S33-delete-first")
                    tap("계속")
                    seen("최종 삭제 확인")
                    capture(folder, "S33-delete-final")
                    tap("취소")
                    seen("나비")
                    seen("초코")
                    capture(folder, "S16-manager-after-cancel")
                }
                assertTrue(File(folder, "S30-settings-compact.png").isFile)
                assertTrue(File(folder, "S16-manager-after-cancel.png").isFile)
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
        listOf("나비" to "ui04-cat", "초코" to "ui04-dog").forEach { (name, id) ->
            database.petDao().insert(
                PetEntity(
                    id = id,
                    name = name,
                    species = if (id == "ui04-cat") "CAT" else "DOG",
                    breed = "",
                    birthday = null,
                    estimatedAgeYears = null,
                    sex = "UNKNOWN",
                    neuterStatus = "UNKNOWN",
                    notes = "",
                    photoFile = null,
                    isArchived = false,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        preferences.selectPet("ui04-cat")
        preferences.setThemeMode(mode)
    }

    private fun dismissLauncherAnr() {
        repeat(3) {
            val wait = device.wait(Until.findObject(By.text("Wait")), 700)
            if (wait != null && device.hasObject(By.textContains("Pixel Launcher"))) {
                wait.click()
                device.waitForIdle()
            }
        }
    }

    private fun seen(text: String) {
        dismissLauncherAnr()
        checkNotNull(device.wait(Until.findObject(By.textContains(text)), 12_000)) {
            "UI-04 화면에 해당 문구가 없습니다: $text"
        }
    }

    private fun tap(text: String) {
        repeat(6) {
            dismissLauncherAnr()
            val target = device.wait(Until.findObject(By.text(text)), 3_000)
            if (target != null) {
                try {
                    target.click()
                    device.waitForIdle()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    Thread.sleep(200)
                }
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 16)
        }
        error("UI-04 버튼 클릭 실패: $text")
    }

    private fun capture(folder: File, name: String) {
        device.waitForIdle()
        Thread.sleep(350)
        val file = File(folder, "$name.png")
        check(device.takeScreenshot(file) && file.length() > 10_000) {
            "UI-04 캡처 실패: $name"
        }
    }
}
