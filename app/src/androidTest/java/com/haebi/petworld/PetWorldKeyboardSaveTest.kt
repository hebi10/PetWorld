package com.haebi.petworld

import android.content.Context
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PetWorldKeyboardSaveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database = PetWorldDatabase.get(context)
    private val preferences = ThemePreferences(context)
    private val root = File(context.getExternalFilesDir(null), "ui06e-keyboard")

    private data class ImeState(val visible: Boolean, val bottomInset: Int)

    @Test
    fun saveButtonStaysAccessibleWithKeyboardAcrossFiveCareForms() {
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { mkdirs() }
                for (label in listOf("식사", "음수", "배변", "활동", "체중")) {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        mustSee("나비의 하루")
                        tap("빠른 기록 추가")
                        tap(label)
                        mustSee("나비 · $label 기록")
                        mustSee("기록 저장")
                        capture(folder, "$label-keyboard-closed")

                        focusNote()
                        val ime = awaitIme(scenario)
                        val save = checkNotNull(
                            device.wait(Until.findObject(By.text("기록 저장")), 4_000)
                        ) { "$label: 키보드 표시 중 저장 버튼을 찾을 수 없습니다." }
                        val saveBounds = save.visibleBounds
                        val dp = context.resources.displayMetrics.density
                        val keyboardTop = device.displayHeight - ime.bottomInset
                        check(saveBounds.width() > dp * 48f &&
                            saveBounds.height() > dp * 14f &&
                            saveBounds.left >= 0 &&
                            saveBounds.right <= device.displayWidth &&
                            saveBounds.bottom <= keyboardTop + dp * 8f) {
                            "$label: 저장 버튼이 키보드와 겹칩니다. " +
                                "button=$saveBounds, keyboardTop=$keyboardTop, ime=$ime"
                        }
                        capture(folder, "$label-keyboard-open")

                        if (label == "식사") {
                            tap("기록 저장")
                            mustSee("급여량을 올바르게 입력해 주세요.")
                            capture(folder, "$label-validation-visible")
                        }
                    }
                }
            }
        } finally {
            runBlocking {
                database.petDao().deleteAll()
                preferences.selectPet(null)
                preferences.setThemeMode(ThemeMode.SYSTEM)
            }
        }
        check(File(root, "light/식사-keyboard-open.png").isFile)
        check(File(root, "dark/체중-keyboard-open.png").isFile)
    }

    private fun seed(mode: ThemeMode) = runBlocking {
        database.petDao().deleteAll()
        preferences.selectPet(null)
        preferences.setThemeMode(mode)
        val now = System.currentTimeMillis()
        database.petDao().insert(
            PetEntity(
                id = "ui06e-cat", name = "나비", species = "CAT", breed = "",
                birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
                neuterStatus = "UNKNOWN", notes = "", photoFile = null,
                isArchived = false, createdAt = now, updatedAt = now,
            )
        )
        preferences.selectPet("ui06e-cat")
    }

    private fun mustSee(text: String) {
        checkNotNull(device.wait(Until.findObject(By.textContains(text)), 12_000)) {
            "화면에서 '$text' 항목을 찾지 못했습니다."
        }
    }

    private fun tap(text: String) {
        repeat(5) {
            val node = device.wait(Until.findObject(By.text(text)), 2_000)
            if (node != null) {
                try {
                    node.click()
                    device.waitForIdle()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    Thread.sleep(250)
                }
            }
        }
        error("버튼 클릭 실패: $text")
    }

    private fun focusNote() {
        val centerX = device.displayWidth / 2
        val scrollStart = device.displayHeight * 3 / 4
        val scrollEnd = device.displayHeight / 3
        repeat(16) {
            val memo = device.findObject(By.textContains("관찰 메모"))
            if (memo != null && memo.visibleBounds.height() > 0 &&
                memo.visibleBounds.centerY() < scrollStart) {
                try {
                    memo.click()
                    device.waitForIdle()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // Retry after scrolling.
                }
            }
            device.swipe(centerX, scrollStart, centerX, scrollEnd, 20)
            device.waitForIdle()
        }
        error("관찰 메모 필드를 찾거나 선택하지 못했습니다.")
    }

    private fun awaitIme(scenario: ActivityScenario<MainActivity>): ImeState {
        repeat(3) { attempt ->
            repeat(20) {
                var state = ImeState(false, 0)
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    state = ImeState(
                        insets?.isVisible(WindowInsetsCompat.Type.ime()) == true,
                        insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
                    )
                }
                if (state.visible &&
                    state.bottomInset > context.resources.displayMetrics.density * 120f
                ) {
                    return state
                }
                Thread.sleep(250)
            }
            if (attempt < 2) {
                focusNote()
                device.waitForIdle()
            }
        }
        error("실제 Android 소프트 키보드가 표시되지 않았습니다.")
    }

    private fun capture(folder: File, name: String) {
        device.waitForIdle()
        Thread.sleep(250)
        val output = File(folder, "$name.png")
        check(device.takeScreenshot(output) && output.length() > 10_000) {
            "실제 화면 캡처 실패: $name"
        }
    }
}
