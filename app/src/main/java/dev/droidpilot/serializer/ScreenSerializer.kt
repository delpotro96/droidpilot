package dev.droidpilot.serializer

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement

// Turns the view tree into the numbered text listing the planner reads
object ScreenSerializer {

    // What the planner is shown
    private const val MAX_ELEMENTS = 80

    // How far collection goes before giving up, so a pathological tree cannot
    // stall the walk. Kept above MAX_ELEMENTS so overflow is detectable
    private const val MAX_SCAN = 400
    private const val MAX_DEPTH = 40

    fun serialize(root: AccessibilityNodeInfo?, packageName: String, activity: String?): ScreenState {
        val collected = mutableListOf<UiElement>()
        if (root != null) walk(root, 0, collected)

        val elements = collected.take(MAX_ELEMENTS)
        return ScreenState(
            packageName = packageName,
            activity = activity,
            elements = elements,
            screenHash = hashOf(packageName, activity, elements) { it.label.orEmpty() },
            structureHash = hashOf(packageName, activity, elements.filter { it.clickable || it.editable }) {
                it.label.orEmpty()
            },
            truncated = collected.size > elements.size
        )
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<UiElement>) {
        if (depth > MAX_DEPTH || out.size >= MAX_SCAN) return
        if (!node.isVisibleToUser) return
        // Ids are assigned on the way in, so the index is the collection order

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val role = roleOf(node)
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (isWorthKeeping(node, role, text, desc)) {
            out += UiElement(
                id = out.size,
                role = role,
                text = text,
                desc = desc,
                viewId = node.viewIdResourceName,
                bounds = bounds,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable
            )
        }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i) ?: continue, depth + 1, out)
        }
    }

    // A node with no label that cannot be operated is noise to the planner
    private fun isWorthKeeping(
        node: AccessibilityNodeInfo,
        role: Role,
        text: String?,
        desc: String?
    ): Boolean {
        if (node.isClickable || node.isScrollable || node.isEditable || node.isCheckable) return true
        if (role == Role.SURFACE || role == Role.WEBVIEW) return true
        return !text.isNullOrBlank() || !desc.isNullOrBlank()
    }

    private fun roleOf(node: AccessibilityNodeInfo): Role {
        val cls = node.className?.toString().orEmpty()
        return when {
            cls.endsWith("SurfaceView") || cls.endsWith("GLSurfaceView") || cls.endsWith("TextureView") -> Role.SURFACE
            cls.contains("WebView") -> Role.WEBVIEW
            node.isEditable || cls.endsWith("EditText") -> Role.INPUT
            cls.endsWith("Switch") || cls.endsWith("ToggleButton") -> Role.SWITCH
            cls.endsWith("CheckBox") || cls.endsWith("RadioButton") -> Role.CHECKBOX
            cls.endsWith("Button") || cls.endsWith("ImageButton") -> Role.BUTTON
            cls.endsWith("RecyclerView") || cls.endsWith("ListView") || node.isScrollable -> Role.LIST
            cls.endsWith("ImageView") -> Role.IMAGE
            cls.endsWith("TextView") -> Role.TEXT
            node.isClickable -> Role.BUTTON
            else -> Role.OTHER
        }
    }

    private fun hashOf(
        pkg: String,
        activity: String?,
        elements: List<UiElement>,
        label: (UiElement) -> String
    ): String {
        val sig = buildString {
            append(pkg).append("|").append(activity.orEmpty()).append("|")
            elements.forEach { append(it.role).append(":").append(label(it)).append(";") }
        }
        return sig.hashCode().toUInt().toString(16)
    }

    // Rendered verbatim into the planner prompt
    fun toPrompt(state: ScreenState): String = buildString {
        append("app: ").append(state.packageName).append("\n")
        state.activity?.let { append("screen: ").append(it).append("\n") }
        if (state.elements.isEmpty()) {
            append("(no elements - vision mode required)\n")
            return@buildString
        }
        state.elements.forEach { e ->
            append("[").append(e.id).append("] ")
            append(e.role.name.lowercase())
            e.label?.let { append(" \"").append(it.take(60)).append("\"") }
            val flags = buildList {
                if (e.clickable) add("clickable")
                if (e.scrollable) add("scrollable")
                if (e.editable) add("editable")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(")")
            append("\n")
        }
        if (state.truncated) {
            append("(more elements exist below - swipe to reach them)\n")
        }
    }
}
