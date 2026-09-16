package dev.droidpilot.diag

import android.util.Base64
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.observer.ScreenshotProbe
import kotlinx.serialization.Serializable

// The whole of what was observed, not the abbreviation the planner is shown.
//
// The listing in the app drops bounds and resource ids, which are exactly the
// fields the policy decides on. A chat screen was read off that listing and the
// conclusion that message bubbles sit inside the scrolling list was a guess,
// because there were no coordinates to check it against
@Serializable
data class ScreenDump(
    val packageName: String,
    val activity: String?,
    val screenHash: String,
    val structureHash: String,
    val textUsable: Boolean,
    val truncated: Boolean,
    val elements: List<DumpElement>,
    val capture: DumpCapture,
    val takenAt: Long
) {
    companion object {
        fun of(state: ScreenState): ScreenDump = ScreenDump(
            packageName = state.packageName,
            activity = state.activity,
            screenHash = state.screenHash,
            structureHash = state.structureHash,
            textUsable = state.isTextUsable,
            truncated = state.truncated,
            elements = state.elements.map { element ->
                DumpElement(
                    id = element.id,
                    role = element.role.name,
                    text = element.text,
                    desc = element.desc,
                    viewId = element.viewId,
                    left = element.bounds.left,
                    top = element.bounds.top,
                    right = element.bounds.right,
                    bottom = element.bounds.bottom,
                    clickable = element.clickable,
                    scrollable = element.scrollable,
                    editable = element.editable
                )
            },
            capture = capture(state),
            takenAt = state.capturedAt
        )

        // Whether this screen can be photographed is the one thing the view
        // tree can never say, and a game behind a security solution is the
        // whole reason it matters
        private fun capture(state: ScreenState): DumpCapture {
            val bytes = state.screenshot
                ?: return DumpCapture(attempted = !state.isTextUsable, bytes = 0)

            return DumpCapture(
                attempted = true,
                bytes = bytes.size,
                blank = ScreenshotProbe.isBlank(bytes),
                pngBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            )
        }
    }
}

@Serializable
data class DumpElement(
    val id: Int,
    val role: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean
)

@Serializable
data class DumpCapture(
    // False means the screen was readable as text, so none was ever requested
    val attempted: Boolean,
    val bytes: Int,
    val blank: Boolean = false,
    val pngBase64: String? = null
)
