package com.xencode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.xencode.feature.onboarding.data.OnboardingRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Captures the app's home page (the `chat` start destination) as a PNG, by launching the real
 * [MainActivity] under Robolectric and rendering the real Compose hierarchy — no emulator needed.
 *
 * The output lands at `$TMPDIR/xencode-mobile-home.png` (printed with a SCREENSHOT_SAVED marker so
 * the CI/dev workflow can promote it into `docs/screenshots/`).
 *
 * Onboarding is pre-seeded COMPLETED so the home page renders instead of the first-launch overlay.
 *
 * Capture strategy: try the Compose-test `captureToImage` (PixelCopy path) from the test thread —
 * calling it on the main thread deadlocks the frame clock. If the frame pump is unavailable, fall
 * back to a synchronous measure/layout/draw of the decor view into a bitmap, which is deterministic
 * under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class HomeScreenshotTest {

    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Test
    fun capturesHomeScreen() {
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        // Skip the first-launch onboarding overlay so the capture shows the home page itself.
        runBlocking { OnboardingRepository(app).complete() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* first creation done */ }
            composeRule.waitForIdle()
            // Pump a few frame callbacks so Compose has drawn at least once before capture.
            repeat(6) { shadowOf(Looper.getMainLooper()).idle() }

            val bitmap = try {
                composeRule.onRoot().captureToImage().asAndroidBitmap()
            } catch (_: Throwable) {
                // Deterministic fallback: draw the full decor hierarchy synchronously.
                // (Throwable: ComposeTimeoutException subclasses differ across ui-test versions.)
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val width = decor.measuredWidth.takeIf { it > 0 } ?: (411f * 420 / 160).toInt()
                    val height = decor.measuredHeight.takeIf { it > 0 } ?: (891f * 420 / 160).toInt()
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    decor.layout(0, 0, width, height)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bmp))
                    return@onActivity saveBitmap(bmp)
                }
                return
            }
            saveBitmap(bitmap)
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val out = File(System.getProperty("java.io.tmpdir"), "xencode-mobile-home.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT_SAVED=${out.absolutePath}")
    }
}
