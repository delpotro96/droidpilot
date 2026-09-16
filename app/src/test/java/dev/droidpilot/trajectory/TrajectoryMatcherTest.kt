package dev.droidpilot.trajectory

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Role
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrajectoryMatcherTest {

    @Test
    fun `a different app never matches`() {
        val recorded = ScreenSignature("com.example.app", "MainActivity", listOf("Send"))
        val live = screen(packageName = "com.other.app", elements = listOf(element(0, "Send")))

        assertEquals(0.0, TrajectoryMatcher.signatureScore(recorded, live), 0.001)
        assertFalse(TrajectoryMatcher.matches(recorded, live))
    }

    @Test
    fun `changed list contents still match when the anchors survive`() {
        val recorded = ScreenSignature("com.example.app", "ChatList", listOf("New chat", "Search"))
        val live = screen(
            activity = "ChatList",
            elements = listOf(
                element(0, "New chat"),
                element(1, "Search"),
                // Message previews differ on every run and must not break the match
                element(2, "lunch at 12?", role = Role.TEXT, clickable = false),
                element(3, "see you then", role = Role.TEXT, clickable = false)
            )
        )

        assertTrue(TrajectoryMatcher.matches(recorded, live))
    }

    @Test
    fun `losing most anchors is treated as divergence`() {
        val recorded = ScreenSignature(
            "com.example.app", "MainActivity",
            listOf("Send", "Attach", "Camera", "Emoji")
        )
        val live = screen(elements = listOf(element(0, "Send")))

        assertEquals(0.25, TrajectoryMatcher.signatureScore(recorded, live), 0.001)
        assertFalse(TrajectoryMatcher.matches(recorded, live))
    }

    @Test
    fun `an element is found by label after the layout shifts`() {
        val ref = ElementRef("Send", Role.BUTTON, 0, 0, 100, 50)
        val live = screen(elements = listOf(element(0, "Send", left = 0, top = 120, right = 100, bottom = 170)))

        assertEquals(0, TrajectoryMatcher.resolve(ref, live)?.id)
    }

    @Test
    fun `a label that moved across the screen is rejected`() {
        val ref = ElementRef("Send", Role.BUTTON, 0, 0, 100, 50)
        val live = screen(elements = listOf(element(0, "Send", left = 900, top = 1800, right = 1000, bottom = 1850)))

        assertNull(TrajectoryMatcher.resolve(ref, live))
    }

    @Test
    fun `an unlabelled icon falls back to the nearest element of the same role`() {
        val ref = ElementRef(null, Role.IMAGE, 0, 0, 100, 50)
        val live = screen(
            elements = listOf(
                element(0, null, role = Role.BUTTON, left = 0, top = 0, right = 100, bottom = 50),
                element(1, null, role = Role.IMAGE, left = 10, top = 10, right = 110, bottom = 60)
            )
        )

        assertEquals(1, TrajectoryMatcher.resolve(ref, live)?.id)
    }

    @Test
    fun `a recorded label never resolves to a different label`() {
        val ref = ElementRef("Cancel", Role.BUTTON, 0, 0, 100, 50)
        // Something else took the exact spot the Cancel button used to occupy
        val live = screen(elements = listOf(element(0, "Delete", left = 0, top = 0, right = 100, bottom = 50)))

        assertNull(TrajectoryMatcher.resolve(ref, live))
    }

    @Test
    fun `an unlabelled reference never resolves to a labelled element`() {
        val ref = ElementRef(null, Role.BUTTON, 0, 0, 100, 50)
        val live = screen(elements = listOf(element(0, "Delete", left = 0, top = 0, right = 100, bottom = 50)))

        assertNull(TrajectoryMatcher.resolve(ref, live))
    }

    @Test
    fun `a recorded tap becomes a tap on the current element id`() {
        val recorded = RecordedAction.Tap(ElementRef("Send", Role.BUTTON, 0, 0, 100, 50))
        val live = screen(elements = listOf(element(0, "Other"), element(1, "Send")))

        assertEquals(AgentAction.Tap(1), TrajectoryMatcher.toAction(recorded, live))
    }

    @Test
    fun `a recorded tap on a missing element yields nothing`() {
        val recorded = RecordedAction.Tap(ElementRef("Send", Role.BUTTON, 0, 0, 100, 50))
        val live = screen(elements = listOf(element(0, "Other", left = 800, top = 900, right = 900, bottom = 950)))

        assertNull(TrajectoryMatcher.toAction(recorded, live))
    }

}
