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
        ActivityScenario.launch(PetWorldAccountActivity::class.java).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            assertTrue("계정 제목이 표시되어야 합니다", device.wait(Until.hasObject(By.text("계정")), 30_000))
            assertTrue("계정 상태가 표시되어야 합니다", device.wait(Until.hasObject(By.text("계정 상태")), 10_000))
            assertTrue("비회원 상태가 표시되어야 합니다", device.wait(Until.hasObject(By.text("로그인하지 않음")), 10_000))

            fun assertVisibleByScrolling(label: String) {
                if (!device.hasObject(By.text(label))) {
                    UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(6)
                        .scrollIntoView(UiSelector().text(label))
                }
                assertTrue("${label} 표시 필요", device.wait(Until.hasObject(By.text(label)), 10_000))
            }
            assertVisibleByScrolling("이 기기의 기록")
            assertVisibleByScrolling("Google 계정으로 시작")
            assertVisibleByScrolling("로그인 없이 사용")
            assertFalse("비회원에게 계정 삭제 진입 버튼이 표시되면 안 됩니다",
                device.hasObject(By.text("계정 삭제")))
        }
    }
}
