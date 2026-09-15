package dev.droidpilot.policy

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import dev.droidpilot.core.model.Verdict

// Evaluated before anything reaches the screen, whatever the planner asked for.
//
// Two severities, deliberately unequal. Denying a screen costs the whole run,
// so it takes an unambiguous checkout phrase. Asking costs one dialog, so
// anything that smells of money or of an action that cannot be undone asks.
// An earlier version scored keyword coverage as a fraction of label length,
// which was wrong in both directions at once: "결제했어?" in a chat scored 0.40
// and locked the conversation, while "Confirm and pay" scored 0.20 and went
// straight through. Length is not what separates a button from a sentence.
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

    // A checkout screen is denied outright, but only on a phrase that cannot
    // mean anything else. A bottom navigation tab reading Pay, or a message
    // about having paid, must not cost the agent the whole run
    private fun screenVerdict(action: AgentAction, state: ScreenState): Verdict? {
        // Leaving is how the agent gets off a payment screen. Denying the way
        // out strands it there with nothing it is allowed to do
        if (action is AgentAction.Back || action is AgentAction.Home) return null

        val hit = state.elements.firstOrNull { isControl(it, state) && matches(it, CHECKOUT_PHRASES) }
        return hit?.let { Verdict.Deny("checkout screen: " + it.describe) }
    }

    // Asking is cheap, so this net is wide. It covers the element the planner
    // named and any control drawn on top of it, because the label of a button
    // is routinely a child of the view that handles the press and the planner
    // may name either one
    private fun actionVerdict(action: AgentAction, state: ScreenState): Verdict {
        val named = when (action) {
            is AgentAction.Tap -> state.elements.getOrNull(action.elementId)
            is AgentAction.LongPress -> state.elements.getOrNull(action.elementId)
            else -> null
        } ?: return Verdict.Allow

        val group = overlapping(named, state)
        group.firstOrNull { matches(it, IRREVERSIBLE_WORDS) }?.let {
            return Verdict.RequireConfirm("cannot be undone: " + it.describe)
        }
        group.firstOrNull { matches(it, MONEY_WORDS) }?.let {
            return Verdict.RequireConfirm("spends money: " + it.describe)
        }
        return Verdict.Allow
    }

    // The named element plus whatever shares its space. A wrapper with no label
    // and a child carrying the text are one control to a person, and judging
    // only the one the model happened to name left the other unguarded
    private fun overlapping(named: UiElement, state: ScreenState): List<UiElement> =
        listOf(named) + state.elements.filter {
            it.id != named.id && (it.bounds.contains(named.bounds) || named.bounds.contains(it.bounds))
        }

    // A control is something a person can press. Prose is not, even when a
    // scrolling container above it happens to be clickable
    private fun isControl(element: UiElement, state: ScreenState): Boolean {
        if (element.role == Role.TEXT && !element.clickable) {
            // Unless a control is drawn exactly around it, which is how a
            // labelled button appears in the tree
            return state.elements.any {
                it.clickable && it.role != Role.LIST && it.bounds.contains(element.bounds)
            }
        }
        return element.clickable || element.role == Role.BUTTON
    }

    private fun matches(element: UiElement, keywords: List<String>): Boolean {
        val label = element.label
        if (label != null) {
            return label.length <= MAX_CONTROL_LABEL && containsKeyword(label, keywords)
        }
        // Only consulted when the element says nothing, which is the case the
        // fallback exists for: an icon with no contentDescription. Reading it
        // alongside a label turns a row in a payment history into a checkout
        return element.clickable && matchesId(element.idName, keywords)
    }

    // Korean has no word boundaries to anchor on, so a substring is all there
    // is. ASCII keywords are anchored, or Resend would read as send
    private fun containsKeyword(label: String, keywords: List<String>): Boolean =
        keywords.any { keyword ->
            if (keyword.isAscii()) anchored(keyword).containsMatchIn(label)
            else label.contains(keyword)
        }

    private fun String.isAscii(): Boolean = all { it.code < ASCII_LIMIT }

    private fun anchored(keyword: String): Regex =
        anchoredCache.getOrPut(keyword) {
            Regex("(?<![A-Za-z])" + Regex.escape(keyword) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
        }

    // Resource ids are developer-written words joined by separators or by case,
    // so whole word matching is the right test. delete_button and deleteButton
    // both match, deleted_items_count does not
    private fun matchesId(idName: String?, keywords: List<String>): Boolean {
        if (idName.isNullOrBlank()) return false

        val words = CAMEL_BOUNDARY.replace(idName, " $1")
            .split(*ID_SEPARATORS, ' ')
            .filter { it.isNotBlank() }

        return words.any { word -> keywords.any { it.equals(word, ignoreCase = true) } }
    }

    companion object {
        // Longer than any button label, shorter than a sentence
        const val MAX_CONTROL_LABEL = 24

        private const val ASCII_LIMIT = 128
        private val ID_SEPARATORS = charArrayOf('_', '-', '.')
        private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])([A-Z])")

        // Compiled once per keyword rather than on every element of every screen
        private val anchoredCache = mutableMapOf<String, Regex>()

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

        // Unambiguous enough to cost the whole run. A single word never is
        private val CHECKOUT_PHRASES = listOf(
            "결제하기", "구매하기", "주문하기", "결제 진행", "결제하시겠",
            "checkout", "check out", "place order", "place your order",
            "complete purchase", "confirm and pay", "pay now", "buy now"
        )

        // Worth a question before the agent spends anything
        private val MONEY_WORDS = listOf(
            "결제", "송금", "이체", "출금", "카드 등록", "간편결제", "구매", "주문",
            "pay", "payment", "purchase", "buy", "order", "checkout"
        )

        // 확인 is the OK button of nearly every Korean confirmation dialog, and
        // that dialog is the last thing between the agent and the act
        private val IRREVERSIBLE_WORDS = listOf(
            "삭제", "지우기", "탈퇴", "초기화", "전송", "보내기", "나가기", "확인",
            "delete", "remove", "erase", "send", "submit", "confirm", "reset",
            "discard", "leave", "unsubscribe"
        )
    }
}
