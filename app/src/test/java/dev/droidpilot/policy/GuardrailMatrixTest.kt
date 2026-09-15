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

    private val denied: (Verdict) -> Boolean = { it is Verdict.Deny }
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
        assertAllDescriptionsAgree("Checkout", "checkout_button", denied, "checkout")
    }

    @Test
    fun `a korean payment button is caught however it is described`() {
        assertAllDescriptionsAgree("결제하기", "purchase_button_container", denied, "결제")
    }

    @Test
    fun `an abbreviated pay id is caught`() {
        assertAllDescriptionsAgree("Pay", "btn_pay", denied, "pay")
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
        // The id names the row, not a control. What the element says decides,
        // and it says the date of a past payment
        val verdict = verdict("2026-09-01 결제 완료", "com.app:id/payment_history_row")

        assertTrue("expected Allow, got " + verdict, verdict == Verdict.Allow)
    }

    @Test
    fun `an unlabelled control still falls back to its id`() {
        val verdict = verdict(null, "com.app:id/checkout_button")

        assertTrue("expected Deny, got " + verdict, verdict is Verdict.Deny)
    }
}
