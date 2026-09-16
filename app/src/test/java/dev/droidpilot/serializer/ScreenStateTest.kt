package dev.droidpilot.serializer

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.Role
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenStateTest {

    @Test
    fun `a normal screen is drivable from the text listing`() {
        val state = screen(
            elements = listOf(element(0, "A"), element(1, "B"), element(2, "C"))
        )

        assertTrue(state.isTextUsable)
    }

    @Test
    fun `a game surface forces vision even when buttons are present`() {
        val state = screen(
            elements = listOf(
                element(0, "A"),
                element(1, "B"),
                element(2, "C"),
                element(3, null, role = Role.SURFACE)
            )
        )

        assertFalse(state.isTextUsable)
    }

    @Test
    fun `a screen with nothing to press forces vision`() {
        val state = screen(elements = listOf(element(0, "Only text", clickable = false)))

        assertFalse(state.isTextUsable)
    }

    @Test
    fun `an empty screen forces vision`() {
        assertFalse(screen().isTextUsable)
    }

    @Test
    fun `the prompt numbers every element and marks its flags`() {
        val state = screen(
            elements = listOf(
                element(0, "Send"),
                element(1, "Message", role = Role.INPUT, editable = true, clickable = false)
            )
        )

        val prompt = ScreenSerializer.toPrompt(state)

        assertTrue(prompt.contains("app: com.example.app"))
        assertTrue(prompt.contains("[0] button \"Send\" (clickable)"))
        assertTrue(prompt.contains("[1] input \"Message\" (editable)"))
    }

    @Test
    fun `an empty screen tells the planner that vision is required`() {
        assertTrue(ScreenSerializer.toPrompt(screen()).contains("vision mode required"))
    }

    @Test
    fun `a truncated listing says so instead of looking complete`() {
        val state = screen(elements = listOf(element(0, "First")), truncated = true)

        assertTrue(ScreenSerializer.toPrompt(state).contains("too long to list"))
    }

    @Test
    fun `a complete listing carries no truncation notice`() {
        val state = screen(elements = listOf(element(0, "First")))

        assertFalse(ScreenSerializer.toPrompt(state).contains("more elements exist"))
    }

    @Test
    fun `a long label is truncated so one element cannot flood the prompt`() {
        val state = screen(elements = listOf(element(0, "x".repeat(200))))

        val line = ScreenSerializer.toPrompt(state).lines().first { it.startsWith("[0]") }

        assertTrue(line.length < 100)
    }
}
