package dev.droidpilot.core.model

// What the person is actually being asked.
//
// The question used to be the policy's reason and nothing else - "cannot be
// undone: 전송". Approving that tells you a send is about to happen and not who
// it is going to, which is the only part anyone would want to check. A small
// model picks the wrong conversation often enough that the question has to
// carry the answer to that.
object ConfirmQuestion {

    // Labels long enough to be a message rather than a title
    private const val MAX_HEADLINE = 60
    private const val HEADLINES = 2

    fun of(reason: String, state: ScreenState): String {
        val where = headline(state)
        return if (where.isEmpty()) reason else reason + "\n" + where
    }

    // The top of the screen, which on every messaging and shopping app is the
    // name of whoever or whatever is about to be acted on. Read from position
    // rather than from role, because a chat title is a plain text node in one
    // app and a button in the next
    private fun headline(state: ScreenState): String {
        val top = state.elements
            .filter { it.label != null }
            .sortedBy { it.bounds.top }
            .take(HEADLINES)
            .mapNotNull { it.label?.take(MAX_HEADLINE)?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .distinct()

        val app = state.packageName
        if (top.isEmpty()) return "in " + app
        return "in " + app + ", on: " + top.joinToString(" / ")
    }
}
