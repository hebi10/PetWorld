package com.haebi.petworld

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/** Focused PR design regression. Synthetic records only, no login or Firebase. */
@RunWith(AndroidJUnit4::class)
class PetWorldPrimaryResponsiveDesignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val db = PetWorldDatabase.get(context)
    private val prefs = ThemePreferences(context)
    private var diagnosticsFolder: File? = null

    @Test fun captureResponsivePrimaryTabs() {
        val layoutCase = requireNotNull(InstrumentationRegistry.getArguments().getString("layoutCase"))
        require(layoutCase in setOf("compact160", "fold-unfolded", "fold-folded"))
        val root = File(context.getExternalFilesDir(null), "design-responsive/$layoutCase")
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { check(mkdirs()) }
                diagnosticsFolder = folder
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val cfg = activity.resources.configuration
                        when (layoutCase) {
                            "compact160" -> check(cfg.screenWidthDp in 320..380 && cfg.fontScale in 1.55f..1.65f)
                            "fold-unfolded" -> check(cfg.screenWidthDp >= 600 && cfg.fontScale < 1.3f)
                            "fold-folded" -> check(cfg.screenWidthDp < 600 && cfg.fontScale < 1.3f)
                        }
                        File(folder, "configuration.txt").writeText(
                            "widthDp=${cfg.screenWidthDp}\nheightDp=${cfg.screenHeightDp}\nfontScale=${cfg.fontScale}\n"
                        )
                    }
                    // Wait for loaded profile rather than accepting a transient startup frame.
                    see("나비의 하루")
                    if (layoutCase == "fold-unfolded") assertPaneOrder("오늘의 돌봄", "최근 기록")
                    capture(folder, "D01-home")
                    ensure("빠른 기록 추가")
                    tap("빠른 기록 추가")
                    see("나비의 기록 추가")
                    listOf("식사", "음수", "배변", "활동", "체중").forEach { see(it) }
                    capture(folder, "D01-quick-record")
                    tap("닫기")
                    tap("기록")
                    see("나비의 생활 기록")
                    see("기간: 7일")
                    if (layoutCase == "fold-unfolded") assertPaneOrder("나비의 생활 기록", "기록을 선택해 주세요")
                    capture(folder, "D02-records")
                    tap("유형: 전체")
                    see("기록 유형")
                    tap("음수")
                    see("유형: 음수")
                    ensure("120mL")
                    tap("120mL")
                    ensure("음수 기록")
                    capture(folder, "D02-record-detail")
                    ensure("목록으로")
                    tap("목록으로")
                    tap("건강")
                    see("나비의 체중·건강")
                    ensure("최근 체중")
                    capture(folder, "D03-health")
                    ensure("체중 기록 추가")
                    tap("체중 기록 추가")
                    see("나비 · 체중 기록")
                    tap("닫기")
                    tap("통계")
                    see("나비의 기록 통계")
                    ensure("체중 변화")
                    if (layoutCase == "fold-unfolded") assertPaneOrder("체중 변화", "생활 기록 요약")
                    capture(folder, "D04-statistics")
                    ensure("생활 기록 요약")
                    ensure("배변")
                    if (layoutCase == "compact160") swipe(true)
                    tap("더보기")
                    assertMoreHeadingAtTop()
                    see("프로필 관리")
                    capture(folder, "D05-more")
                    ensure("화면 설정")
                    tap("화면 설정")
                    see("화면 모드")
                    ensure("시스템")
                    ensure("라이트")
                    ensure("다크")
                    capture(folder, "D05-theme-picker")
                    tap("닫기")
                    ensure("백업 파일 복원")
                    capture(folder, "D05-data")
                    tap("암호화 백업 생성")
                    see("백업 암호 설정")
                    tap("취소")
                    tap("홈")
                    ensure("빠른 기록 추가")
                }
            }
        } finally {
            runBlocking {
                db.petDao().deleteAll()
                prefs.selectPet(null)
                prefs.setThemeMode(ThemeMode.SYSTEM)
            }
        }
    }

    private fun seed(mode: ThemeMode) = runBlocking {
        db.petDao().deleteAll()
        val now = System.currentTimeMillis()
        listOf("나비" to "design-cat", "초코" to "design-dog").forEach { (name,id) ->
            db.petDao().insert(PetEntity(id = id, name = name, species = if (name=="나비") "CAT" else "DOG",
                breed = "", birthday = null, estimatedAgeYears = null, sex = "UNKNOWN", neuterStatus = "UNKNOWN",
                notes = if (name=="나비") "건강 상태를 관찰하고 있습니다." else "", photoFile = null,
                isArchived = false, createdAt = now, updatedAt = now))
        }
        prefs.selectPet("design-cat")
        prefs.setThemeMode(mode)
        val zone = ZoneId.systemDefault().id
        listOf(
            CareDraft(petId = "design-cat", type = CareType.FOOD, occurredAt = now-120000, timezoneId=zone, amount="40", consumedAmount="30"),
            CareDraft(petId = "design-cat", type = CareType.WATER, occurredAt = now-60000, timezoneId=zone, amount="120"),
            CareDraft(petId = "design-cat", type = CareType.ELIMINATION, occurredAt = now-180000, timezoneId=zone, amount="1"),
            CareDraft(petId = "design-cat", type = CareType.ACTIVITY, occurredAt = now-240000, timezoneId=zone, amount="25"),
            CareDraft(petId = "design-cat", type = CareType.WEIGHT, occurredAt = now-300000, timezoneId=zone, amount="4.5"),
            CareDraft(petId = "design-cat", type = CareType.WEIGHT, occurredAt = now-86400000, timezoneId=zone, amount="4.4"),
        ).forEach { db.careDao().insert(it.toEntity(now=now)) }
    }


    private fun recover() {
        if (device.hasObject(By.textContains("Pixel Launcher"))) {
            device.findObject(By.text("Wait"))?.click()
            device.waitForIdle()
        }
    }

    private fun failWithEvidence(message: String): Nothing {
        diagnosticsFolder?.let { folder ->
            runCatching {
                device.waitForIdle()
                device.takeScreenshot(File(folder, "failure.png"))
                device.dumpWindowHierarchy(File(folder, "failure-hierarchy.xml"))
                File(folder, "failure.txt").writeText(message)
            }
        }
        error(message)
    }
    private fun see(value: String) {
        repeat(12) {
            recover()
            if (device.wait(Until.hasObject(By.textContains(value)), 2000)) return
        }
        failWithEvidence("Required visible text missing: $value")
    }
    private fun swipe(down: Boolean) {
        val mid = device.displayWidth / 2
        val low = device.displayHeight / 4
        val high = device.displayHeight * 3 / 4
        device.swipe(mid, if (down) high else low, mid, if (down) low else high, 20)
        device.waitForIdle()
    }
    private fun ensure(value: String) {
        for (down in listOf(true, false)) repeat(10) {
            recover()
            if (device.wait(Until.hasObject(By.textContains(value)), 800)) return
            swipe(down)
        }
        failWithEvidence("Required scrollable text missing: $value")
    }
    private fun tap(value: String) {
        for (down in listOf(true, false)) repeat(7) {
            recover()
            val item = device.wait(Until.findObject(By.text(value)), 1500)
            if (item != null) {
                try { item.click(); device.waitForIdle(); return }
                catch (_: androidx.test.uiautomator.StaleObjectException) { Thread.sleep(200) }
            }
            swipe(down)
        }
        failWithEvidence("Required action unavailable: $value")
    }
    private fun assertMoreHeadingAtTop() {
        repeat(8) {
            recover()
            device.waitForIdle()
            if (device.findObjects(By.text("더보기")).any { node ->
                val bounds = node.visibleBounds
                bounds.height() > 0 && bounds.top < device.displayHeight / 3
            }) return
            Thread.sleep(300)
        }
        failWithEvidence("More heading is not visible at the top after switching tabs")
    }
    private fun assertPaneOrder(left: String, right: String) {
        val l = checkNotNull(device.wait(Until.findObject(By.textContains(left)), 5000))
        val r = checkNotNull(device.wait(Until.findObject(By.textContains(right)), 5000))
        check(l.visibleBounds.centerX() < r.visibleBounds.centerX()) { "Two-pane order is incorrect: $left / $right" }
    }
    private fun capture(folder: File, name: String) {
        device.waitForIdle()
        Thread.sleep(450)
        val out = File(folder, "$name.png")
        check(device.takeScreenshot(out) && out.length() > 10000) { "Screenshot failed: $name" }
    }
}
