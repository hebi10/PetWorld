package com.haebi.petworld

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.haebi.petworld.auth.PetWorldAccountActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseDevAccountSmokeTest {
    @Test
    fun accountScreenShowsGuestAndLocalOnlyStatusWithoutStartingGoogleLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val appPackage = instrumentation.targetContext.packageName
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")

        ActivityScenario.launch(PetWorldAccountActivity::class.java).use { scenario ->
            device.waitForIdle(5_000)

            fun assertVisible(label: String, timeout: Long = 10_000) {
                val visible = device.wait(Until.hasObject(By.pkg(appPackage).text(label)), timeout)
                assertTrue(
                    "$label 표시 필요 (foreground=" + device.currentPackageName +
                        ", screenOn=" + device.isScreenOn + ", lifecycle=" + scenario.state + ")",
                    visible,
                )
            }

            assertVisible("계정", 30_000)
            assertVisible("계정 상태")
            assertVisible("로그인하지 않음")
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
            assertVisibleByScrolling("로그인 없이 사용")
            assertFalse("비회원에게 계정 삭제 진입 버튼이 표시되면 안 됩니다",
                device.hasObject(By.pkg(appPackage).text("계정 삭제")))
        }
    }
}
