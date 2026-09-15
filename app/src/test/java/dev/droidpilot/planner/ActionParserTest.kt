package dev.droidpilot.planner

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionParserTest {

    @Test
    fun `a tap carries the element id`() {
        assertEquals(
            AgentAction.Tap(3),
            ActionParser.parse("""{"action":"tap","elementId":3}""").getOrThrow()
        )
    }

    @Test
    fun `input carries both the element and the text`() {
        assertEquals(
            AgentAction.Input(2, "hello there"),
            ActionParser.parse("""{"action":"input","elementId":2,"text":"hello there"}""").getOrThrow()
        )
    }

    @Test
    fun `a direction is accepted in any case`() {
        assertEquals(
            AgentAction.Swipe(Direction.DOWN),
            ActionParser.parse("""{"action":"swipe","direction":"DOWN"}""").getOrThrow()
        )
    }

    @Test
    fun `a swipe can name the list it applies to`() {
        assertEquals(
            AgentAction.Swipe(Direction.DOWN, 4),
            ActionParser.parse("""{"action":"swipe","direction":"down","elementId":4}""").getOrThrow()
        )
    }

    @Test
    fun `a swipe without a target still parses`() {
        assertEquals(
            AgentAction.Swipe(Direction.UP, null),
            ActionParser.parse("""{"action":"swipe","direction":"up"}""").getOrThrow()
        )
    }

    @Test
    fun `actions without arguments parse`() {
        assertEquals(AgentAction.Back, ActionParser.parse("""{"action":"back"}""").getOrThrow())
        assertEquals(AgentAction.Home, ActionParser.parse("""{"action":"home"}""").getOrThrow())
    }

    @Test
    fun `wait falls back to a default when the server omits the duration`() {
        assertEquals(AgentAction.Wait(1000), ActionParser.parse("""{"action":"wait"}""").getOrThrow())
    }

    @Test
    fun `terminal actions carry their message`() {
        assertEquals(
            AgentAction.AskUser("which chat?"),
            ActionParser.parse("""{"action":"ask","question":"which chat?"}""").getOrThrow()
        )
        assertEquals(
            AgentAction.Done("sent"),
            ActionParser.parse("""{"action":"done","summary":"sent"}""").getOrThrow()
        )
    }

    @Test
    fun `an answer wrapped in prose is still read`() {
        val raw = """Sure, here is the action:
            |```json
            |{"action":"tap","elementId":7}
            |```
            |Let me know if that helps.""".trimMargin()

        assertEquals(AgentAction.Tap(7), ActionParser.parse(raw).getOrThrow())
    }

    @Test
    fun `braces inside a string do not end the object early`() {
        val action = ActionParser.parse("""{"action":"input","elementId":1,"text":"use {} braces"}""").getOrThrow()

        assertEquals(AgentAction.Input(1, "use {} braces"), action)
    }

    @Test
    fun `an unknown action fails instead of guessing`() {
        assertTrue(ActionParser.parse("""{"action":"explode"}""").isFailure)
    }

    @Test
    fun `a tap without an element id fails rather than defaulting to zero`() {
        assertTrue(ActionParser.parse("""{"action":"tap"}""").isFailure)
    }

    @Test
    fun `a response with no json at all fails`() {
        assertTrue(ActionParser.parse("I am not sure what to do here.").isFailure)
    }

    @Test
    fun `a truncated response fails`() {
        assertTrue(ActionParser.parse("""{"action":"tap","elementId":""").isFailure)
    }
}
