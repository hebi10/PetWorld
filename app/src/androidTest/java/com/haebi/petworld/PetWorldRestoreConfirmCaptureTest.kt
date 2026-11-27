package com.haebi.petworld

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.backup.PetWorldBackupExporter
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** QA-only synthetic file selection, warning and cancellation on a fresh API35 emulator. */
@RunWith(AndroidJUnit4::class)
class PetWorldRestoreConfirmCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val prefs = ThemePreferences(context)
    private val database = PetWorldDatabase.get(context)
    private val root = File(context.getExternalFilesDir(null), "audit-e-restore")

    @Test fun captureRestoreWarningAndCancelInBothThemes() {
        check(Build.VERSION.SDK_INT >= 29 && context.packageName == "com.haebi.petworld")
        check(runBlocking {
            database.petDao().list().isEmpty() && database.careDao().listAll().isEmpty()
        }) { "Use a fresh synthetic QA emulator; refusing to change existing records." }
        check(!PetWorldRecoveryJournal(context).hasPending())
        val oldTheme = runBlocking { prefs.themeMode.first() }
        val oldSelected = runBlocking { prefs.selectedPetId.first() }
        val originalId = "audit-e-local-" + UUID.randomUUID()
        val backupId = "audit-e-backup-" + UUID.randomUUID()
        val password = "synthetic-restore-qa".toCharArray()
        val tempName = "audit-e-backup-" + UUID.randomUUID() + ".db"
        val fixture = Room.databaseBuilder(context, PetWorldDatabase::class.java, tempName).build()
        val fileName = "PetWorld-D22-QA-" + UUID.randomUUID().toString().take(8) + ".petworld"
        var document: Uri? = null
        root.mkdirs()
        try {
            runBlocking {
                fixture.petDao().insert(pet(backupId, "백업용 QA 고양이"))
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                document = checkNotNull(context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                val uri = checkNotNull(document)
                PetWorldBackupExporter(context, fixture, prefs).exportToUri(uri, password)
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                database.petDao().insert(pet(originalId, "현재 QA 고양이"))
                prefs.selectPet(originalId)
            }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                runBlocking { prefs.setThemeMode(theme) }
                val folder = File(root, theme.name.lowercase()).apply { mkdirs() }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    device.wakeUp()
                    device.executeShellCommand("wm dismiss-keyguard")
                    checkNotNull(device.wait(Until.findObject(By.text("홈")), 20_000))
                    tap("더보기")
                    show("백업 파일 복원")
                    tap("백업 파일 복원")
                    checkNotNull(device.wait(Until.findObject(By.pkg("com.google.android.documentsui")), 15_000))
                    var file = device.wait(Until.findObject(By.text(fileName)), 4_000)
                    if (file == null) {
                        val roots = device.findObject(By.desc("Show roots"))
                            ?: device.findObject(By.desc("Open from"))
                            ?: device.findObject(By.desc("루트 표시"))
                        checkNotNull(roots) { "Document picker roots unavailable." }.click()
                        val downloads = device.wait(Until.findObject(By.text("Downloads")), 3_000)
                            ?: device.wait(Until.findObject(By.text("다운로드")), 3_000)
                        checkNotNull(downloads).click()
                        file = device.wait(Until.findObject(By.text(fileName)), 10_000)
                    }
                    checkNotNull(file) { "Synthetic backup was not found." }.click()
                    show("백업 암호 입력")
                    val field = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
                    field.text = String(password)
                    // Close only the IME, never use Back to dismiss the dialog itself.
                    repeat(20) {
                        var visible = false
                        scenario.onActivity { activity ->
                            val decor = activity.window.decorView
                            visible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                            if (visible) WindowCompat.getInsetsController(activity.window, decor)
                                .hide(WindowInsetsCompat.Type.ime())
                        }
                        if (visible) Thread.sleep(100)
                    }
                    tap("다음")
                    show("기존 데이터를 교체할까요?")
                    show("교체 범위")
                    show("데이터 교체 및 복원")
                    device.waitForIdle()
                    val png = File(folder, "D22-restore-confirm.png")
                    assertTrue(device.takeScreenshot(png) && png.length() > 10000)
                    device.dumpWindowHierarchy(File(folder, "D22-restore-confirm.xml"))
                    // Never press the destructive action.
                    tap("뒤로")
                    show("백업 암호 입력")
                    tap("취소")
                    show("백업 파일 복원")
                }
                assertEquals(listOf(originalId), runBlocking { database.petDao().list().map { it.id } })
                assertFalse(PetWorldRecoveryJournal(context).hasPending())
            }
        } finally {
            password.fill('\u0000')
            document?.let { context.contentResolver.delete(it, null, null) }
            fixture.close()
            context.deleteDatabase(tempName)
            runBlocking {
                database.petDao().delete(originalId)
                prefs.selectPet(oldSelected)
                prefs.setThemeMode(oldTheme)
            }
        }
    }

    private fun pet(id: String, name: String) = PetEntity(
        id = id, name = name, species = "CAT", breed = "",
        birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
        neuterStatus = "UNKNOWN", notes = "", photoFile = null, isArchived = false,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun tap(label: String) {
        checkNotNull(device.wait(Until.findObject(By.text(label)), 10_000)) { "Missing: $label" }.click()
        device.waitForIdle()
    }

    private fun show(label: String) {
        repeat(10) {
            if (device.wait(Until.hasObject(By.textContains(label)), 1000)) return
            val x = device.displayWidth / 8
            device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 20)
            device.waitForIdle()
        }
        error("Missing visible label: $label")
    }
}
