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
        // Nothing here presses anything. Wait does reach the executor, but it
        // only sleeps
        when (action) {
            is AgentAction.AskUser, is AgentAction.Done,
            is AgentAction.Fail, is AgentAction.Wait -> return Verdict.Allow
            else -> Unit
        }

        packageVerdict(state)?.let { return it }
        screenVerdict(action, state)?.let { return it }
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

    // On a checkout screen every press is dangerous, so the screen is denied.
    //
    // Not filtered on clickable: the label of a pay button is usually a child
    // of the view that handles the press, which is the same layout the executor
    // climbs for
    private fun screenVerdict(action: AgentAction, state: ScreenState): Verdict? {
        // Leaving is how the agent gets off a payment screen. Denying the way
        // out strands it there with nothing it is allowed to do
        if (action is AgentAction.Back || action is AgentAction.Home) return null

        val hit = state.elements.firstOrNull { matches(it, PAYMENT_KEYWORDS) }
        return hit?.let { Verdict.Deny("looks like a payment screen: " + it.describe) }
    }

    private fun actionVerdict(action: AgentAction, state: ScreenState): Verdict {
        // Typing the word delete into a field is not the destructive act
        val element = when (action) {
            is AgentAction.Tap -> state.elements.getOrNull(action.elementId)
            is AgentAction.LongPress -> state.elements.getOrNull(action.elementId)
            else -> null
        } ?: return Verdict.Allow

        if (matches(element, IRREVERSIBLE_KEYWORDS)) {
            return Verdict.RequireConfirm("irreversible action: " + element.describe)
        }
        return Verdict.Allow
    }

    // The label and the resource id are judged separately. They are different
    // kinds of string, and joining them made the denominator of the coverage
    // test grow with how descriptive the id was - the clearer the evidence, the
    // more certainly the rule was skipped
    private fun matches(element: UiElement, keywords: List<String>): Boolean =
        matchesLabel(element.label, keywords) || matchesId(element.idName, keywords)

    // A control is mostly its verb. Prose that mentions the verb is not a
    // control, and length alone cannot separate "Proceed to checkout" from a
    // chat preview of the same length
    private fun matchesLabel(label: String?, keywords: List<String>): Boolean {
        if (label.isNullOrBlank() || label.length > MAX_CONTROL_LABEL) return false

        val longest = keywords
            .filter { label.contains(it, ignoreCase = true) }
            .maxByOrNull { it.length }
            ?: return false

        return longest.length.toDouble() / label.trim().length >= MIN_KEYWORD_COVERAGE
    }

    // Resource ids are developer-written words joined by separators, so whole
    // word matching is the right test. delete_button matches, avatar does not,
    // and deleted_items_count does not become a delete button by containing it
    private fun matchesId(idName: String?, keywords: List<String>): Boolean {
        if (idName.isNullOrBlank()) return false

        val words = idName.split(*ID_SEPARATORS).filter { it.isNotBlank() }
        return words.any { word -> keywords.any { it.equals(word, ignoreCase = true) } }
    }

    companion object {
        // Longer than any button label, shorter than a sentence
        const val MAX_CONTROL_LABEL = 24

        // Tuned against real button labels and chat previews of similar length.
        // Still a guess until it has been seen against a real view tree
        const val MIN_KEYWORD_COVERAGE = 0.4

        private val ID_SEPARATORS = charArrayOf('_', '-', '.')

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
            "payment", "checkout", "purchase", "pay", "buy"
        )

        private val IRREVERSIBLE_KEYWORDS = listOf(
            "삭제", "탈퇴", "초기화", "전송", "보내기",
            "delete", "remove", "send", "submit", "confirm", "reset"
        )
    }
}
