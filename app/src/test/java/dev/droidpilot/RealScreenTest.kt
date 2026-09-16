package dev.droidpilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.GridPoint
import dev.droidpilot.core.model.Risk
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.UiElement
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.planner.ActionParser
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.serializer.ScreenSerializer
import dev.droidpilot.trajectory.TrajectoryRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Built from two dumps taken on a Galaxy S25+ rather than from imagination.
// Every threshold in this project was an estimate until these arrived, and both
// screens broke something that sixteen green tests had been calling correct
@RunWith(AndroidJUnit4::class)
class RealScreenTest {

    private val policy = SafetyPolicy()

    // com.blackdust.redblue on co.ab180.airbridge.unity.AirbridgeActivity
    // produced exactly one element. This is the whole of it
    private fun gameScreen() = screen(
        packageName = "com.blackdust.redblue",
        activity = "co.ab180.airbridge.unity.AirbridgeActivity",
        elements = listOf(
            element(
                0, "Game view", role = Role.SURFACE,
                left = 0, top = 0, right = 1080, bottom = 2340,
                clickable = false
            )
        )
    )

    // com.kakao.talk listed sixty-five elements, and every message bubble came
    // back as a clickable button carrying its text directly
    private fun chatScreen(vararg messages: String): List<UiElement> {
        val list = element(
            0, role = Role.LIST, left = 0, top = 200, right = 1080, bottom = 2000,
            clickable = false, scrollable = true
        )
        val bubbles = messages.mapIndexed { index, text ->
            element(
                index + 1, text, role = Role.BUTTON,
                left = 100, top = 300 + index * 120, right = 900, bottom = 380 + index * 120
            )
        }
        val compose = element(
            messages.size + 1, role = Role.INPUT,
            left = 0, top = 2100, right = 900, bottom = 2200, editable = true
        )
        val send = element(
            messages.size + 2, "전송", role = Role.BUTTON,
            left = 900, top = 2100, right = 1080, bottom = 2200
        )
        return listOf(list) + bubbles + listOf(compose, send)
    }

    @Test
    fun `a game exposes one surface and cannot be read as text`() {
        val state = gameScreen()

        assertFalse(state.isTextUsable)
    }

    @Test
    fun `the prompt for a game asks for a point instead of listing the surface`() {
        val prompt = ScreenSerializer.toPrompt(gameScreen())

        // Listing the surface as element zero made the planner tap it, which is
        // the centre of the screen and means nothing
        assertFalse(prompt.contains("[0]"))
        assertTrue(prompt.contains("tapAt"))
    }

    @Test
    fun `a point press is allowed where there is nothing to name`() {
        val verdict = policy.check(AgentAction.TapAt(GridPoint(500, 640)), gameScreen())

        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `a point press is refused where the elements can be named`() {
        val readable = screen(elements = chatScreen("hello", "hi"))

        val verdict = policy.check(AgentAction.TapAt(GridPoint(500, 640)), readable)

        assertTrue(verdict is Verdict.Deny)
    }

    @Test
    fun `a declared risk still asks on a game screen`() {
        val verdict = policy.check(
            AgentAction.TapAt(GridPoint(500, 640), Risk.SPENDS),
            gameScreen()
        )

        assertTrue(verdict is Verdict.RequireConfirm)
    }

    @Test
    fun `a message that reads like a checkout button does not lock the conversation`() {
        // The one case the size ratio cannot catch: the bubble is the button,
        // so it is not a label sitting inside a larger row
        val state = screen(elements = chatScreen("결제하기 눌러", "ㅇㅋ"))
        val compose = state.elements.first { it.editable }

        val verdict = policy.check(AgentAction.Tap(compose.id), state)

        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `a real checkout button is asked about rather than denied`() {
        val elements = chatScreen("hello") + element(
            99, "결제하기", role = Role.BUTTON,
            left = 0, top = 2200, right = 1080, bottom = 2340
        )
        val state = screen(packageName = "com.some.shop", elements = elements)

        val verdict = policy.check(AgentAction.Tap(elements.size - 1), state)

        // Denying was tried and could not be aimed: it locked chat screens and
        // still missed a shopping page held inside one scroll view
        assertTrue(verdict is Verdict.RequireConfirm)
    }

    @Test
    fun `a short conversation is not locked either`() {
        // The bubbles fit, so the list reports itself unscrollable and every
        // structural test that told content from chrome stopped working
        val state = screen(
            packageName = "com.kakao.talk",
            elements = listOf(
                element(0, role = Role.LIST, left = 0, top = 200, right = 1080, bottom = 2000,
                    clickable = false, scrollable = false),
                element(1, "결제하기 눌러봐", role = Role.BUTTON,
                    left = 100, top = 300, right = 900, bottom = 380),
                element(2, role = Role.INPUT, left = 0, top = 2100, right = 900, bottom = 2200,
                    editable = true)
            )
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(2), state))
    }

    @Test
    fun `a buy button beside a video cannot be reached by point`() {
        // Not text usable, because of the player surface. The button is still
        // listed, and by point it was reaching it without being read
        val state = screen(
            packageName = "com.naver.shoppinglive",
            elements = listOf(
                element(0, "player", role = Role.SURFACE,
                    left = 0, top = 0, right = 1080, bottom = 1400, clickable = false),
                element(1, "구매", role = Role.BUTTON,
                    left = 0, top = 1900, right = 1080, bottom = 2000)
            )
        )

        assertTrue(policy.check(AgentAction.TapAt(GridPoint(500, 833)), state) is Verdict.Deny)
        assertTrue(policy.check(AgentAction.Tap(1), state) is Verdict.RequireConfirm)
    }

    @Test
    fun `a page of text fields cannot grow the listing past its cap`() {
        val many = (0 until 300).map { element(it, "field " + it, editable = true) }

        assertTrue(ScreenSerializer.choose(many).size <= 84)
    }

    @Test
    fun `the compose bar survives a conversation too long to list`() {
        val bubbles = (0 until 200).map { element(it, "message " + it) }
        val compose = element(200, role = Role.INPUT, editable = true)
        val send = element(201, "전송")

        val kept = ScreenSerializer.choose(bubbles + compose + send)

        // Taking the first eighty in tree order dropped both of these, which is
        // every control a reply needs
        assertTrue(kept.any { it.editable })
        assertTrue(kept.any { it.label == "전송" })
    }

    @Test
    fun `truncating the middle renumbers what is left so an index still resolves`() {
        val elements = (0 until 200).map { element(it, "row " + it) }

        val kept = ScreenSerializer.choose(elements)

        // Every lookup in the executor is elements[id], so a gap here would
        // press a different row than the one the policy vetted
        kept.forEachIndexed { index, element -> assertEquals(index, element.id) }
    }

    @Test
    fun `a run that pressed a point is not stored for replay`() {
        val recorder = TrajectoryRecorder("collect the daily reward")

        recorder.record(AgentAction.TapAt(GridPoint(500, 640)), gameScreen())

        // A game gives the signature no anchors, so it would match on the
        // activity name alone - one name covering every screen the game has
        assertNull(recorder.build())
    }

    @Test
    fun `one unrecordable step discards the whole path`() {
        val state = screen(elements = chatScreen("hello"))
        val recorder = TrajectoryRecorder("reply")

        recorder.record(AgentAction.Tap(0), state)
        recorder.record(AgentAction.TapAt(GridPoint(1, 1)), gameScreen())

        // Keeping the first step alone would store a path whose second screen
        // is never reached
        assertNull(recorder.build())
    }

    @Test
    fun `the surface is hidden from the listing and refused if named anyway`() {
        // Hiding it from the prompt does not renumber the ids, so the number is
        // still there to be asked for, and pressing it is the middle of the
        // screen - the exact press the omission exists to prevent
        val verdict = policy.check(AgentAction.Tap(0), gameScreen())

        assertTrue(verdict is Verdict.Deny)
    }

    @Test
    fun `the bottom of the screen keeps its numbers when a message arrives`() {
        val compose = { id: Int ->
            element(id, role = Role.INPUT, left = 0, top = 2100, right = 900, bottom = 2200, editable = true)
        }
        val send = { id: Int -> element(id, "전송", left = 900, top = 2100, right = 1080, bottom = 2200) }
        val bubbles = { count: Int ->
            (0 until count).map {
                element(it, "msg " + it, left = 100, top = 300 + it, right = 900, bottom = 380 + it)
            }
        }

        val quiet = ScreenSerializer.choose(bubbles(200) + compose(200) + send(201))
        val busy = ScreenSerializer.choose(bubbles(201) + compose(201) + send(202))

        // Anchored to the end of the tree, one arriving message shifted every
        // tail id, the structure hash moved with it, and an active conversation
        // spent its restart budget without ever acting
        assertEquals(
            quiet.indexOfFirst { it.editable },
            busy.indexOfFirst { it.editable }
        )
        assertEquals(
            quiet.indexOfFirst { it.label == "전송" },
            busy.indexOfFirst { it.label == "전송" }
        )
    }

    @Test
    fun `a step that ran and failed discards the path rather than omitting itself`() {
        val state = screen(elements = chatScreen("hello"))
        val recorder = TrajectoryRecorder("reply")

        recorder.record(AgentAction.Tap(0), state)
        // The set text call was rejected, after the field had already taken
        // focus. Replaying what is left taps the box and sends an empty message
        recorder.discard()
        recorder.record(AgentAction.Tap(1), state)

        assertNull(recorder.build())
    }

    @Test
    fun `an app is opened by package rather than waiting to be opened by hand`() {
        val action = ActionParser.parse("""{"action":"launch","package":"com.kakao.talk"}""")

        assertEquals(AgentAction.Launch("com.kakao.talk"), action.getOrNull())
    }

    @Test
    fun `opening a blocked app is refused wherever it is asked from`() {
        // The package rules read the screen in front of us, and this action is
        // about somewhere else. Judged on where it goes
        val verdict = policy.check(
            AgentAction.Launch("viva.republica.toss"),
            screen(elements = chatScreen("hello"))
        )

        assertTrue(verdict is Verdict.Deny)
    }

    @Test
    fun `opening an ordinary app needs no permission`() {
        val verdict = policy.check(
            AgentAction.Launch("com.kakao.talk"),
            screen(elements = chatScreen("hello"))
        )

        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `a point survives the grammar round trip`() {
        val action = ActionParser.parse("""{"action":"tapAt","x":250,"y":900,"risk":"none"}""")

        assertEquals(AgentAction.TapAt(GridPoint(250, 900)), action.getOrNull())
    }

    @Test
    fun `a point outside the grid is pulled back to the edge`() {
        val action = ActionParser.parse("""{"action":"longPressAt","x":1400,"y":-30,"risk":"none"}""")

        assertEquals(
            AgentAction.LongPressAt(GridPoint(GridPoint.SIDE, 0)),
            action.getOrNull()
        )
    }

    @Test
    fun `the grid maps onto the display it was laid over`() {
        assertEquals(0f to 0f, GridPoint(0, 0).toPixels(1080, 2340))

        // The far edge lands on the last pixel of the display, not one past it.
        // A gesture at x = 1080 belongs to no window, completes successfully
        // and presses nothing
        assertEquals(1079f to 2339f, GridPoint(1000, 1000).toPixels(1080, 2340))
    }

    @Test
    fun `a recorded pixel position converts to the same point on any display`() {
        val onPhone = GridPoint.of(540, 1170, 1080, 2340)
        val onTablet = GridPoint.of(1200, 2000, 2400, 4000)

        assertEquals(onPhone, onTablet)
        assertNotNull(onPhone)
    }
}
