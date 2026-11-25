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

/** Real Activity + existing Room APIs, same six-record fixture as baseline full capture. */
@RunWith(AndroidJUnit4::class)
class PetWorldPrimaryTabsDesignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val db = PetWorldDatabase.get(context)
    private val prefs = ThemePreferences(context)
    private val root = File(context.getExternalFilesDir(null), "design-primary-tabs")

    @Test fun captureFiveTabsAndVerifyExistingActions() {
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                seed(mode)
                val folder = File(root, mode.name.lowercase()).apply { mkdirs() }
                ActivityScenario.launch(MainActivity::class.java).use {
                    see("나비의 하루")
                    see("오늘의 돌봄")
                    see("120mL")
                    capture(folder, "D01-home")
                    tap("자세히 보기")
                    see("기간: 오늘")
                    tap("홈")
                    see("나비의 하루")
                    tap("빠른 기록 추가")
                    see("나비의 기록 추가")
                    tap("닫기")
                    see("나비의 하루")
                    tap("기록")
                    see("나비의 생활 기록")
                    tap("기간: 오늘")
                    tap("7일")
                    see("기록 6건")
                    see("120mL")
                    capture(folder, "D02-records")
                    tap("기간: 7일")
                    see("조회 기간")
                    tap("닫기")
                    tap("유형: 전체")
                    see("기록 유형")
                    tap("닫기")

                    tap("건강")
                    see("나비의 체중·건강")
                    see("최근 체중")
                    see("4.5")
                    see("변화량 +0.1kg")
                    capture(folder, "D03-health")
                    tap("체중 기록 추가")
                    see("나비 · 체중 기록")
                    tap("닫기")

                    tap("통계")
                    see("나비의 기록 통계")
                    see("체중 변화")
                    tap("7일")
                    see("최근 측정 4.5kg")
                    see("최저 4.4kg · 최고 4.5kg")
                    capture(folder, "D04-statistics")
                    swipe(true)
                    ensure("생활 기록 요약")
                    capture(folder, "D04-statistics-summary")
                    ensure("배변")
                    capture(folder, "D04-statistics-more")

                    tap("더보기")
                    see("프로필 관리")
                    see("화면 설정")
                    capture(folder, "D05-more")
                    tap("화면 설정")
                    see("화면 모드")
                    see("시스템")
                    see("라이트")
                    see("다크")
                    capture(folder, "D05-theme-picker")
                    tap(if (mode == ThemeMode.LIGHT) "라이트" else "다크")
                    see("화면 설정")
                    ensure("백업 파일 복원")
                    capture(folder, "D05-more-data")
                    tap("암호화 백업 생성")
                    see("백업 암호 설정")
                    tap("취소")
                    ensure("앱 정보")
                    tap("앱 정보")
                    see("PetWorld")
                    tap("닫기")
                    // Actual nav must remain available after dialogs.
                    tap("홈")
                    see("나비의 하루")
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
    private fun see(value: String) {
        repeat(8) {
            recover()
            if (device.wait(Until.hasObject(By.textContains(value)), 2000)) return
        }
        error("Visible screen text missing: $value")
    }
    private fun ensure(value: String) {
        for (down in listOf(true,false)) repeat(8) {
            recover()
            if (device.wait(Until.hasObject(By.textContains(value)), 500)) return
            swipe(down)
        }
        error("Scrollable screen text missing: $value")
    }
    private fun swipe(down: Boolean) {
        val mid=device.displayWidth/2
        val low=device.displayHeight/4
        val high=device.displayHeight*3/4
        device.swipe(mid,if(down)high else low,mid,if(down)low else high,20)
        device.waitForIdle()
    }
    private fun tap(value: String) {
        for (down in listOf(true,false)) repeat(6) {
            recover()
            val item=device.wait(Until.findObject(By.text(value)), 1500)
            if(item!=null) {
                try { item.click();device.waitForIdle();return }
                catch (_: androidx.test.uiautomator.StaleObjectException) { Thread.sleep(200) }
            }
            swipe(down)
        }
        error("Action unavailable: $value")
    }
    private fun capture(folder:File,name:String) {
        device.waitForIdle()
        Thread.sleep(450)
        val out=File(folder,"$name.png")
        check(device.takeScreenshot(out)&&out.length()>10000) {"Screenshot failed: $name"}
    }
}
