package dev.droidpilot.policy

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.ScreenState
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

    // On a checkout screen every tap is dangerous, so the whole screen is denied
    private fun screenVerdict(state: ScreenState): Verdict? {
        val labels = state.elements.mapNotNull { it.label }
        val hit = labels.firstOrNull { label -> PAYMENT_KEYWORDS.any { label.contains(it, ignoreCase = true) } }
        return hit?.let { Verdict.Deny("looks like a payment screen: " + it) }
    }

    private fun actionVerdict(action: AgentAction, state: ScreenState): Verdict {
        val label = when (action) {
            is AgentAction.Tap -> state.elements.getOrNull(action.elementId)?.label
            is AgentAction.LongPress -> state.elements.getOrNull(action.elementId)?.label
            else -> null
        } ?: return Verdict.Allow

        IRREVERSIBLE_KEYWORDS.firstOrNull { label.contains(it, ignoreCase = true) }?.let {
            return Verdict.RequireConfirm("irreversible action: " + label)
        }
        return Verdict.Allow
    }

    companion object {
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
