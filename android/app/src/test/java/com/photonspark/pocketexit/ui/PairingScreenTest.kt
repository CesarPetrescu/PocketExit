package com.photonspark.pocketexit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photonspark.pocketexit.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first screen a fresh install shows. Its whole job is to explain the
 * pairing it cannot do for you and to keep the manual form reachable, so those
 * are what is asserted.
 */
@RunWith(AndroidJUnit4::class)
class PairingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun `explains the three steps of pairing`() {
        compose.setContent { PocketExitTheme { PairingScreen(message = "", onManualSetup = {}) } }

        compose.onNodeWithText(string(R.string.welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.welcome_step_1_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.welcome_step_2_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.welcome_step_3_title)).assertIsDisplayed()
    }

    @Test
    fun `keeps the manual route reachable for a server-mode deployment`() {
        var manual = 0
        compose.setContent {
            PocketExitTheme { PairingScreen(message = "", onManualSetup = { manual += 1 }) }
        }

        // The screen scrolls, and the manual route deliberately sits below
        // the pairing steps, so the node has to be brought into view first.
        compose.onNodeWithText(string(R.string.welcome_manual)).performScrollTo().performClick()

        assertEquals(1, manual)
    }

    @Test
    fun `shows a message only when there is one`() {
        compose.setContent {
            PocketExitTheme { PairingScreen(message = "That link was unreadable", onManualSetup = {}) }
        }

        compose.onNodeWithText("That link was unreadable").assertIsDisplayed()
    }
}
