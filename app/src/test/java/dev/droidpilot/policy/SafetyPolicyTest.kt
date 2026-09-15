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
    fun `every action on a checkout screen is denied, not just the pay button`() {
        val state = screen(
            elements = listOf(
                element(0, "뒤로"),
                element(1, "결제하기")
            )
        )

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
        assertTrue(policy.check(AgentAction.Back, state) is Verdict.Deny)
    }

    @Test
    fun `an english checkout screen is caught too`() {
        val state = screen(elements = listOf(element(0, "Proceed to checkout")))

        assertTrue(policy.check(AgentAction.Tap(0), state) is Verdict.Deny)
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
