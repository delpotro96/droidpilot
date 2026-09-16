package dev.droidpilot.policy

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.Risk
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.core.model.declaredRisk
import dev.droidpilot.core.model.isBlind

// Evaluated before anything reaches the screen, whatever the planner asked for.
//
// The planner declares what its press will do, and that declaration is what
// decides. Four rounds of inferring danger from the label text failed in both
// directions at once - a chat message about having paid locked the whole
// conversation, while Confirm and pay went straight through - because the
// string on screen does not carry the information. The model knows it is
// pressing a delete button.
//
// The keyword rules remain, but only to raise a verdict the planner played
// down. They can never lower one, so a model that lies or simply does not know
// is still caught on the obvious cases, and a model that is honest is not
// second-guessed by a regex.
//
// Only one thing here denies outright, and it is the package. Scanning a whole
// screen for a checkout phrase was tried and removed: a messenger renders
// arbitrary text as controls, so a friend typing 결제하기 locked the entire
// conversation, while a real shopping page - one scroll view filling the window
// with the button inside it - slipped past every structural test meant to tell
// the two apart. Five attempts, false positives and false negatives each time.
// Denying costs the whole run and could not be aimed; asking costs a
// notification and always can. What used to deny now asks.
class SafetyPolicy(
    private val blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES,
    private val allowedPackages: Set<String>? = null,
    // Our own interface. Pressing anything on it is the run operating itself,
    // which at best types the goal into the goal box and at worst presses stop
    private val ownPackage: String? = null
) : Policy {

    override fun check(action: AgentAction, state: ScreenState): Verdict {
        // Nothing here presses anything
        when (action) {
            is AgentAction.AskUser, is AgentAction.Done,
            is AgentAction.Fail -> return Verdict.Allow
            else -> Unit
        }

        launchVerdict(action)?.let { return it }
        ownScreenVerdict(action, state)?.let { return it }
        packageVerdict(state)?.let { return it }
        blindVerdict(action, state)?.let { return it }
        surfaceVerdict(action, state)?.let { return it }

        declaredVerdict(action)?.let { return it }
        return keywordVerdict(action, state)
    }

    // A press aimed at a point exists for screens that expose nothing to
    // address - a game draws its whole interface into one surface. Where there
    // is something to name, aiming at a point instead is the planner going
    // around the only check that reads what is being pressed.
    //
    // The test is whether anything actionable is listed, not whether the screen
    // is text usable. A shopping stream is a video surface with a buy button
    // beside it: not text usable, yet the button is right there in the listing,
    // and by point it was reaching that button unread
    private fun blindVerdict(action: AgentAction, state: ScreenState): Verdict? {
        if (!action.isBlind) return null

        if (!state.hasNameableTarget) return null

        return Verdict.Deny("this screen lists what it can do, press one by number")
    }

    // The package rules read the screen in front of us, and opening an app is
    // the one action whose subject is somewhere else. Judged on where it goes,
    // or the agent could walk into a banking app simply by asking for it
    private fun launchVerdict(action: AgentAction): Verdict? {
        if (action !is AgentAction.Launch) return null

        if (action.packageName in blockedPackages) {
            return Verdict.Deny("blocked app: " + action.packageName)
        }
        if (allowedPackages != null && action.packageName !in allowedPackages) {
            return Verdict.Deny("app not on the allow list: " + action.packageName)
        }
        return null
    }

    // Leaving is always allowed, or a planner that lands here has no legal
    // move and the run dies on its own screen
    private fun ownScreenVerdict(action: AgentAction, state: ScreenState): Verdict? {
        if (ownPackage == null || state.packageName != ownPackage) return null
        return when (action) {
            is AgentAction.Launch, AgentAction.Back, AgentAction.Home -> null
            else -> Verdict.Deny("this is the agent's own screen, open the app the goal needs")
        }
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

    // The surface a game renders into is left out of the listing, because
    // pressing it means pressing the middle of the screen. Leaving it out is
    // not the same as refusing it: the ids are not renumbered, so a planner
    // that names the missing number gets exactly the press the omission was
    // meant to prevent
    private fun surfaceVerdict(action: AgentAction, state: ScreenState): Verdict? {
        val id = when (action) {
            is AgentAction.Tap -> action.elementId
            is AgentAction.LongPress -> action.elementId
            is AgentAction.Input -> action.elementId
            is AgentAction.Swipe -> return null
            else -> return null
        }

        val target = state.elements.getOrNull(id) ?: return null
        if (target.role != Role.SURFACE) return null

        return Verdict.Deny("nothing is known about what is drawn there, aim with a point instead")
    }

    private fun declaredVerdict(action: AgentAction): Verdict? = when (action.declaredRisk) {
        Risk.IRREVERSIBLE -> Verdict.RequireConfirm("the planner called this irreversible")
        Risk.SPENDS -> Verdict.RequireConfirm("the planner said this spends money")
        Risk.NONE -> null
    }

    // The safety net under a planner that called something harmless. It reads
    // only the control being pressed, never the prose around it
    private fun keywordVerdict(action: AgentAction, state: ScreenState): Verdict {
        val named = when (action) {
            is AgentAction.Tap -> state.elements.getOrNull(action.elementId)
            is AgentAction.LongPress -> state.elements.getOrNull(action.elementId)
            // A left swipe across a list row is how most apps delete one
            is AgentAction.Swipe -> action.elementId?.let { state.elements.getOrNull(it) }
            else -> null
        } ?: return Verdict.Allow

        val group = pressedTogether(named, state)
        group.firstOrNull { matches(it, IRREVERSIBLE_WORDS) }?.let {
            return Verdict.RequireConfirm("cannot be undone: " + it.describe)
        }
        group.firstOrNull { matches(it, MONEY_WORDS) }?.let {
            return Verdict.RequireConfirm("spends money: " + it.describe)
        }
        return Verdict.Allow
    }

    // The named element and the label drawn inside it. A button keeps its text
    // in a child, and the planner may name either half, but a list row is not
    // one control with its contents - reading a row that way turned every chat
    // preview into a verdict about the row
    private fun pressedTogether(named: UiElement, state: ScreenState): List<UiElement> =
        listOf(named) + state.elements.filter {
            it.id != named.id &&
                (wraps(named, it) || wraps(it, named))
        }

    // Whether the outer element is a control drawn around the inner one rather
    // than a container that merely holds it somewhere
    private fun wraps(outer: UiElement, inner: UiElement): Boolean =
        outer.bounds.contains(inner.bounds) && area(outer) <= area(inner) * MAX_WRAPPER_RATIO

    private fun area(element: UiElement): Long =
        element.bounds.width().toLong() * element.bounds.height().toLong()

    private fun matches(element: UiElement, keywords: List<String>): Boolean {
        val label = element.label
        if (label != null) return containsKeyword(label, keywords)

        // Only consulted when the element says nothing, which is the case the
        // fallback exists for: an icon with no contentDescription
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
        // A button is a little larger than the text inside it. A list row is
        // many times larger than any one line it holds
        const val MAX_WRAPPER_RATIO = 6

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

        // Worth a question before the agent spends anything. Korean matches as
        // a substring, so 결제 covers 결제하기 and 간편결제 alike
        private val MONEY_WORDS = listOf(
            "결제", "송금", "이체", "출금", "카드 등록", "간편결제", "구매", "주문", "구독",
            "pay", "payment", "purchase", "buy", "order", "checkout", "subscribe"
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
