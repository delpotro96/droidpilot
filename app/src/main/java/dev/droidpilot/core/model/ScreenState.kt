package dev.droidpilot.core.model

import android.graphics.Rect

// One observed frame of the screen
data class ScreenState(
    val packageName: String,
    val activity: String?,
    val elements: List<UiElement>,
    val screenHash: String,
    // Ignores body text, so a clock or an unread badge ticking over does not
    // read as progress. Loop detection keys on this, not on screenHash
    val structureHash: String = screenHash,
    // True when the tree held more than the listing can carry, so a planner
    // that cannot find what it wants knows to scroll rather than give up
    val truncated: Boolean = false,
    val screenshot: ByteArray? = null,

    // The display as it was when this was observed. The planner aims at a
    // point on a grid laid over the screenshot, and the screenshot covers the
    // display, so the press has to be scaled back against the same numbers.
    // Reading them live at press time meant a rotation between the two put the
    // finger somewhere else entirely. Zero means unknown, and the executor
    // falls back to asking the display itself
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val capturedAt: Long = System.currentTimeMillis()
) {
    // Whether the text listing alone is enough to decide the next action
    val isTextUsable: Boolean
        get() = elements.count { it.clickable } >= MIN_CLICKABLE &&
                elements.none { it.role == Role.SURFACE }

    // The generated equals would compare ByteArray by reference
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenState) return false
        return screenHash == other.screenHash && packageName == other.packageName
    }

    override fun hashCode(): Int = 31 * screenHash.hashCode() + packageName.hashCode()

    companion object {
        // Three was a guess, and it was wrong in a way that matters now that a
        // point can be pressed: a dialog offering OK and Cancel reads perfectly
        // and was being called unusable, which both took a screenshot for
        // nothing and let the planner aim at a screen it could have named.
        //
        // The dump that settled it: a Unity game returns one element, a surface
        // that is not even clickable. Zero against one separates the two cases
        // that actually occur, and nothing in between has been observed
        const val MIN_CLICKABLE = 1
    }
}

// A single element the planner can refer to by id
data class UiElement(
    val id: Int,
    val role: Role,
    val text: String?,
    val desc: String?,
    // The developer name for the view, such as com.app:id/delete_button. Often
    // the only clue an unlabelled icon gives about what it does
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean
) {
    // What the element actually shows on screen
    val label: String?
        get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }

    // The developer name without its package, such as delete_button. Judged
    // separately from the label: joining the two into one string made the
    // denominator of any coverage test grow with how descriptive the id was,
    // which let the clearest cases through
    val idName: String?
        get() = viewId?.substringAfterLast("/")?.takeIf { it.isNotBlank() }

    // What a guardrail reports when it refuses
    val describe: String
        get() = label ?: idName ?: ("element " + id)
}

enum class Role {
    BUTTON, TEXT, INPUT, IMAGE, LIST, CHECKBOX, SWITCH, SURFACE, WEBVIEW, OTHER
}
