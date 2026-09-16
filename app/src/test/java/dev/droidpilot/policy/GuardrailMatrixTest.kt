package dev.droidpilot.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// A guardrail regression survived sixteen green tests because every one of
// them supplied a label or a resource id, never both - which is the normal
// case on Android and the case where the rule had stopped firing.
//
// This walks the combinations deliberately: the same button, described three
// ways, has to reach the same verdict every time.
@RunWith(AndroidJUnit4::class)
class GuardrailMatrixTest {

    private val policy = SafetyPolicy()

    private fun verdict(label: String?, viewId: String?): Verdict =
        policy.check(
            AgentAction.Tap(0),
            screen(elements = listOf(element(0, label, viewId = viewId)))
        )

    private fun assertAllDescriptionsAgree(
        label: String,
        viewId: String,
        expectation: (Verdict) -> Boolean,
        what: String
    ) {
        val qualified = "com.example.app:id/" + viewId

        listOf(
            "label only" to verdict(label, null),
            "id only" to verdict(null, qualified),
            "both" to verdict(label, qualified)
        ).forEach { (description, actual) ->
            assertTrue(
                what + " should be caught when described by " + description + ", got " + actual,
                expectation(actual)
            )
        }
    }

    private val confirmed: (Verdict) -> Boolean = { it is Verdict.RequireConfirm }

    @Test
    fun `an english send button is caught however it is described`() {
        assertAllDescriptionsAgree("Send", "send_message_button", confirmed, "send")
    }

    @Test
    fun `a korean send button is caught however it is described`() {
        assertAllDescriptionsAgree("전송", "send_button", confirmed, "전송")
    }

    @Test
    fun `a delete button is caught however it is described`() {
        assertAllDescriptionsAgree("삭제", "delete_button_layout", confirmed, "삭제")
    }

    @Test
    fun `an english checkout button is caught however it is described`() {
        assertAllDescriptionsAgree("Checkout", "checkout_button", confirmed, "checkout")
    }

    // Every one of these used to deny the whole screen. Nothing denies on a
    // label any more, because the same rule locked a conversation the moment a
    // friend typed one of these words. They ask instead
    @Test
    fun `a korean checkout phrase is asked about`() {
        assertTrue(verdict("결제하기", null) is Verdict.RequireConfirm)
        assertTrue(verdict("지금 결제하기", null) is Verdict.RequireConfirm)
        assertTrue(verdict("구매하기", null) is Verdict.RequireConfirm)
    }

    @Test
    fun `an ambiguous money word asks rather than denying`() {
        assertTrue(verdict("Pay", null) is Verdict.RequireConfirm)
        assertTrue(verdict("Pay 12.00", null) is Verdict.RequireConfirm)
        assertTrue(verdict("카카오페이로 결제", null) is Verdict.RequireConfirm)
        assertTrue(verdict(null, "com.app:id/btn_pay") is Verdict.RequireConfirm)
    }

    // The arithmetic that used to decide these is gone. Every one of them was
    // allowed through by a coverage ratio below 0.4
    @Test
    fun `multi word destructive labels ask for confirmation`() {
        listOf(
            "Send message", "메시지 전송", "채팅방 삭제", "모든 데이터 삭제",
            "Delete conversation", "Permanently delete", "회원 탈퇴하기",
            "Reset all settings", "Confirm and pay", "확인"
        ).forEach {
            val actual = verdict(it, null)
            assertTrue(
                it + " should have been questioned, got " + actual,
                actual is Verdict.RequireConfirm || actual is Verdict.Deny
            )
        }
    }

    // And every one of these used to lock the whole screen at exactly 0.40
    @Test
    fun `a short message about money does not deny the screen`() {
        listOf("결제했어?", "송금했어", "이체 완료").forEach {
            val actual = policy.check(
                AgentAction.Tap(1),
                screen(
                    elements = listOf(
                        element(0, it, role = dev.droidpilot.core.model.Role.TEXT, clickable = false),
                        element(1, "Reply")
                    )
                )
            )
            assertTrue(it + " should not have denied the screen, got " + actual, actual !is Verdict.Deny)
        }
    }

    @Test
    fun `an innocent button stays allowed however it is described`() {
        val qualified = "com.example.app:id/avatar_image"

        listOf(
            verdict("Profile", null),
            verdict(null, qualified),
            verdict("Profile", qualified)
        ).forEach { assertTrue("profile should be allowed, got " + it, it == Verdict.Allow) }
    }

    @Test
    fun `a word that merely contains a keyword stays allowed`() {
        listOf(
            verdict("12 items", "com.app:id/deleted_items_count"),
            verdict("Resend later", "com.app:id/pending_label")
        ).forEach { assertTrue("expected Allow, got " + it, it == Verdict.Allow) }
    }

    @Test
    fun `a labelled row in a payment history is not a checkout screen`() {
        // Not a denial - that would cost the whole run on a history list. A
        // question is the right cost for something that mentions money
        val verdict = verdict("2026-09-01 결제 완료", "com.app:id/payment_history_row")

        assertTrue("should not deny the screen, got " + verdict, verdict !is Verdict.Deny)
    }

    @Test
    fun `an unlabelled control still falls back to its id`() {
        val verdict = verdict(null, "com.app:id/checkout_button")

        assertTrue("expected a question, got " + verdict, verdict is Verdict.RequireConfirm)
    }
}
