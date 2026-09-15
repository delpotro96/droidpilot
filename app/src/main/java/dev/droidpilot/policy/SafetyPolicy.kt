package dev.droidpilot.policy

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import dev.droidpilot.core.model.Verdict

// Evaluated before anything reaches the screen, whatever the planner asked for
class SafetyPolicy(
    private val blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES,
    private val allowedPackages: Set<String>? = null
) : Policy {

    override fun check(action: AgentAction, state: ScreenState): Verdict {
        // Terminal actions never touch the screen
        when (action) {
            is AgentAction.AskUser, is AgentAction.Done,
            is AgentAction.Fail, is AgentAction.Wait -> return Verdict.Allow
            else -> Unit
        }

        packageVerdict(state)?.let { return it }
        screenVerdict(state)?.let { return it }
        return actionVerdict(action, state)
    }

    private fun packageVerdict(state: ScreenState): Verdict? {
        if (state.packageName in blockedPackages) {
            return Verdict.Deny("blocked app: " + state.packageName)
        }
        if (allowedPackages != null && state.packageName !in allowedPackages) {
            return Verdict.Deny("app not on the allow list: " + state.packageName)
        }
        return null
    }

    // On a checkout screen every tap is dangerous, so the whole screen is denied.
    //
    // Only button-shaped labels count. Matching every string on screen looked
    // safer but made the agent useless: one chat message saying "결제했어?" was
    // enough to lock the whole conversation out
    private fun screenVerdict(state: ScreenState): Verdict? {
        // Not filtered on clickable. The label of a payment button is very
        // often a child TextView while the parent handles the press, which is
        // the same layout the executor climbs for. Requiring clickable here
        // would miss exactly those screens
        val hit = state.elements
            .filter { isControlSized(it) }
            .firstOrNull { matches(it.identity, PAYMENT_KEYWORDS) }

        return hit?.let { Verdict.Deny("looks like a payment screen: " + it.identity) }
    }

    private fun actionVerdict(action: AgentAction, state: ScreenState): Verdict {
        val element = when (action) {
            is AgentAction.Tap -> state.elements.getOrNull(action.elementId)
            is AgentAction.LongPress -> state.elements.getOrNull(action.elementId)
            is AgentAction.Input -> state.elements.getOrNull(action.elementId)
            else -> null
        } ?: return Verdict.Allow

        // Typing into a field named "delete" is not the destructive act, tapping is
        if (action is AgentAction.Input) return Verdict.Allow

        if (isControlSized(element) && matches(element.identity, IRREVERSIBLE_KEYWORDS)) {
            return Verdict.RequireConfirm("irreversible action: " + element.identity)
        }
        return Verdict.Allow
    }

    // A control carries a short label. Prose that happens to contain the word is
    // body text, not something the agent is about to press. An element with no
    // label at all still has a resource id to judge, which is the only handle
    // an unnamed icon offers
    private fun isControlSized(element: UiElement): Boolean {
        val identity = element.identity
        if (identity.isBlank()) return false
        return element.label == null || element.label!!.length <= MAX_CONTROL_LABEL
    }

    // Length alone cannot separate "Proceed to checkout" from a chat message of
    // the same length, so the keyword also has to account for much of the label.
    // A button is mostly its verb; a sentence that mentions the verb is not
    private fun matches(label: String?, keywords: List<String>): Boolean {
        if (label.isNullOrBlank()) return false

        val longest = keywords
            .filter { label.contains(it, ignoreCase = true) }
            .maxByOrNull { it.length }
            ?: return false

        return longest.length.toDouble() / label.trim().length >= MIN_KEYWORD_COVERAGE
    }

    companion object {
        // Longer than any button label, shorter than a sentence
        const val MAX_CONTROL_LABEL = 24

        // Tuned against real button labels and chat previews of similar length.
        // Both thresholds are guesses until they have been seen against a real
        // view tree
        const val MIN_KEYWORD_COVERAGE = 0.4

        // Banking apps are blocked wholesale, automation has no business there
        val DEFAULT_BLOCKED_PACKAGES = setOf(
            "com.kbstar.kbbank",
            "com.shinhan.sbanking",
            "com.wooribank.smart.npib",
            "com.hanabank.ebk.channel.android.hananbank",
            "com.nh.cashcardapp",
            "com.kakaobank.channel",
            "viva.republica.toss",
            "com.sec.android.app.samsungapps",
            "com.android.vending"
        )

        // Matched against on-screen labels, so the Korean terms stay as-is
        private val PAYMENT_KEYWORDS = listOf(
            "결제", "구매하기", "주문하기", "송금", "이체", "출금", "카드 등록", "간편결제",
            "payment", "checkout", "purchase", "pay now", "buy now"
        )

        private val IRREVERSIBLE_KEYWORDS = listOf(
            "삭제", "탈퇴", "초기화", "전송", "보내기",
            "delete", "remove", "send", "submit", "confirm", "reset"
        )
    }
}
