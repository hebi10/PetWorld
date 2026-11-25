package com.haebi.petworld

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.haebi.petworld.auth.PetWorldAccountActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real guest account UI only, with CI synthetic Firebase configuration. Never signs in. */
@RunWith(AndroidJUnit4::class)
class DesignAccountCaptureTest {
    @Test fun captureGuestAccount() {
        val args = InstrumentationRegistry.getArguments()
        val theme = requireNotNull(args.getString("designTheme"))
        require(theme in setOf("light", "dark"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.getExternalFilesDir(null), "design-account/$theme").apply { mkdirs() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(PetWorldAccountActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("계정")), 30_000))
            assertTrue(device.wait(Until.hasObject(By.text("로그인하지 않음")), 10_000))
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(folder, "A01-account-guest-top.png")))
            if (!device.hasObject(By.text("Google 계정으로 시작"))) {
                UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(6)
                    .scrollIntoView(UiSelector().text("Google 계정으로 시작"))
            }
            assertTrue(device.wait(Until.hasObject(By.text("Google 계정으로 시작")), 10_000))
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(folder, "A01-account-guest-actions.png")))
        }
    }
}
