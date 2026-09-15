package dev.droidpilot.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyPolicyTest {

    private val policy = SafetyPolicy()

    @Test
    fun `an ordinary tap is allowed`() {
        val state = screen(elements = listOf(element(0, "Settings")))

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `a banking app is refused outright`() {
        val state = screen(
            packageName = "viva.republica.toss",
            elements = listOf(element(0, "Home"))
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
    }

    @Test
    fun `every press on a checkout screen is denied, not just the pay button`() {
        val state = screen(
            elements = listOf(
                element(0, "뒤로"),
                element(1, "결제하기")
            )
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
        assertTrue(policy.check(AgentAction.Tap(1), state) is Verdict.Deny)
    }

    @Test
    fun `leaving a checkout screen is allowed, or the agent is stranded on it`() {
        val state = screen(elements = listOf(element(0, "결제하기")))

        assertEquals(Verdict.Allow, policy.check(AgentAction.Back, state))
        assertEquals(Verdict.Allow, policy.check(AgentAction.Home, state))
    }

    @Test
    fun `a labelled button with a matching resource id is still caught`() {
        // Joining label and id into one string used to sink the coverage ratio
        // below the threshold, and the clearer the id the more certainly
        val state = screen(
            elements = listOf(element(0, "Send", viewId = "com.example.app:id/send_message_button"))
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.RequireConfirm)
    }

    @Test
    fun `a checkout button with a matching resource id is still caught`() {
        val state = screen(
            elements = listOf(element(0, "Checkout", viewId = "com.shop:id/checkout_button"))
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
    }

    @Test
    fun `a resource id that merely contains a keyword is not a match`() {
        val state = screen(
            elements = listOf(element(0, "12 items", viewId = "com.app:id/deleted_items_count"))
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `an english checkout screen is caught too`() {
        val state = screen(elements = listOf(element(0, "Checkout")))

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
    }

    @Test
    fun `a chat message mentioning payment does not lock the screen`() {
        val state = screen(
            elements = listOf(
                element(0, "Reply"),
                element(
                    1,
                    "결제했어? 나는 아직 안 했는데 오늘 안에 해야 한다더라",
                    role = Role.TEXT,
                    clickable = false
                )
            )
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `a clickable chat row whose preview mentions payment is not a checkout screen`() {
        val state = screen(
            elements = listOf(
                element(0, "카드 등록은 내일 하자고 전해줘", role = Role.LIST),
                element(1, "New chat")
            )
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(1), state))
    }

    @Test
    fun `deleting asks for confirmation rather than being blocked`() {
        val state = screen(elements = listOf(element(0, "삭제")))

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.RequireConfirm)
    }

    @Test
    fun `sending asks for confirmation`() {
        val state = screen(elements = listOf(element(0, "Send")))

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.RequireConfirm)
    }

    @Test
    fun `typing a word like delete into a field is not a destructive action`() {
        val state = screen(
            elements = listOf(element(0, "Message", role = Role.INPUT, editable = true, clickable = false))
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Input(0, "delete that file"), state))
    }

    @Test
    fun `a long paragraph containing send does not trigger confirmation`() {
        val longLabel = "Tap here to send the weekly summary to everyone on the list"
        val state = screen(elements = listOf(element(0, longLabel)))

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `an unnamed icon is judged by its resource id`() {
        val state = screen(
            elements = listOf(element(0, null, viewId = "com.example.app:id/delete_button", role = Role.IMAGE))
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.RequireConfirm)
    }

    @Test
    fun `an unnamed icon with a harmless resource id passes`() {
        val state = screen(
            elements = listOf(element(0, null, viewId = "com.example.app:id/avatar", role = Role.IMAGE))
        )

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `an element with neither label nor id is not judged`() {
        val state = screen(elements = listOf(element(0, null, role = Role.IMAGE)))

        assertEquals(Verdict.Allow, policy.check(AgentAction.Tap(0), state))
    }

    @Test
    fun `a pay label on a child of the clickable parent is still caught`() {
        val state = screen(
            elements = listOf(
                element(0, null, viewId = "com.shop:id/pay_row", role = Role.BUTTON),
                element(1, "결제하기", role = Role.TEXT, clickable = false)
            )
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
    }

    @Test
    fun `terminal actions bypass the checks`() {
        val state = screen(packageName = "viva.republica.toss", elements = listOf(element(0, "결제하기")))

        assertEquals(Verdict.Allow, policy.check(AgentAction.Done("finished"), state))
        assertEquals(Verdict.Allow, policy.check(AgentAction.AskUser("which one?"), state))
    }

    @Test
    fun `an allow list refuses everything outside it`() {
        val restricted = SafetyPolicy(allowedPackages = setOf("com.example.app"))
        val inside = screen(packageName = "com.example.app", elements = listOf(element(0, "Go")))
        val outside = screen(packageName = "com.example.other", elements = listOf(element(0, "Go")))

        assertEquals(Verdict.Allow, restricted.check(AgentAction.Tap(0), inside))
        assertTrue(restricted.check(AgentAction.Tap(0), outside) is Verdict.Deny)
    }
}
