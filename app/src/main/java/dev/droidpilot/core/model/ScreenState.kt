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
        const val MIN_CLICKABLE = 3
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

    // Everything a guardrail can read about this element, label or not
    val identity: String
        get() = listOfNotNull(label, viewId?.substringAfterLast("/")).joinToString(" ")
}

enum class Role {
    BUTTON, TEXT, INPUT, IMAGE, LIST, CHECKBOX, SWITCH, SURFACE, WEBVIEW, OTHER
}
