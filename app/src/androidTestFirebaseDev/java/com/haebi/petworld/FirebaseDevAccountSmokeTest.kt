package com.haebi.petworld

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.haebi.petworld.auth.PetWorldAccountActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseDevAccountSmokeTest {
    @Test
    fun accountScreenShowsGuestAndLocalOnlyStatusWithoutStartingGoogleLogin() {
        ActivityScenario.launch(PetWorldAccountActivity::class.java).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            assertTrue(device.wait(Until.hasObject(By.text("계정")), 15_000))
            assertTrue(device.wait(Until.hasObject(By.text("계정 상태")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("이 기기의 기록")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("Google 계정으로 시작")), 10_000))
        }
    }
}
