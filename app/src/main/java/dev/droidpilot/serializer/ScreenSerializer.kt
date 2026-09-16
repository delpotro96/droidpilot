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

    // Reserved for the bottom of the screen, where the compose bar, the send
    // button and the primary action live
    private const val TAIL_ELEMENTS = 16

    // How many text fields may be pulled back in from the discarded middle
    private const val EXTRA_EDITABLE = 4

    // Where the bottom of the screen begins. Below this line sit the compose
    // bar, the send button and the primary action, and they stay there
    private const val BOTTOM_BAND = 0.85

    // How far collection goes before giving up, so a pathological tree cannot
    // stall the walk. Kept above MAX_ELEMENTS so overflow is detectable
    private const val MAX_SCAN = 400
    private const val MAX_DEPTH = 40

    fun serialize(
        root: AccessibilityNodeInfo?,
        packageName: String,
        activity: String?,
        displayHeight: Int = 0
    ): ScreenState {
        val collected = mutableListOf<UiElement>()
        if (root != null) walk(root, 0, collected)

        val elements = choose(collected, displayHeight)
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

    // Which elements the planner is shown when there are more than it can read.
    //
    // Taking the first eighty in tree order looked reasonable until a real chat
    // screen was dumped: sixty-five elements, every message bubble its own
    // clickable button, and the text field dead last. A busier conversation
    // overflows well before the walk reaches the bottom bar, so the one control
    // needed to reply was the first thing dropped. Screens put their navigation
    // at the top and their actions at the bottom, and the middle is content, so
    // both ends are kept and the middle gives way.
    //
    // Ids are assigned during the walk, and every lookup is by list index, so
    // anything removed from the middle has to renumber what follows it.
    //
    // Internal rather than private because it is the only part of this file a
    // test can reach without a live view tree
    internal fun choose(collected: List<UiElement>, displayHeight: Int = 0): List<UiElement> {
        if (collected.size <= MAX_ELEMENTS) return collected

        // Chosen by where they sit on screen, not by where they sit in the
        // tree. Anchored to the end of the tree, one arriving message shifted
        // every tail id by one, the structure hash moved with it, and an active
        // conversation burned the restart budget without ever acting. The
        // compose bar does not move down the screen when a message arrives
        // Bounds are not clipped to the display: a wrap_content WebView inside
        // a scroll view reports its whole content height. One of those put the
        // band at 17000 on a 2400px screen, emptied the tail, and dropped the
        // compose bar this function exists to keep
        val floor = displayHeight.takeIf { it > 0 } ?: collected.maxOf { it.bounds.bottom }
        val band = (floor * BOTTOM_BAND).toInt()
        val tail = collected.filter { it.bounds.top >= band }.takeLast(TAIL_ELEMENTS)

        val tailIds = tail.mapTo(mutableSetOf()) { it.id }
        val head = collected.asSequence()
            .filter { it.id !in tailIds }
            .take(MAX_ELEMENTS - tail.size)
            .toList()
        val kept = (head + tail).toMutableList()

        // A form long enough to overflow can still hold its field in the middle,
        // and a screen the agent cannot type into is not one it can finish.
        // Capped, or a page of three hundred inputs re-adds every one of them
        // and the listing the cap exists to bound is unbounded again
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        collected.asSequence()
            .filter { it.editable && it.id !in keptIds }
            .take(EXTRA_EDITABLE)
            .forEach { kept += it }

        return kept.sortedBy { it.id }
            .mapIndexed { index, element ->
                if (element.id == index) element else element.copy(id = index)
            }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<UiElement>) {
        if (depth > MAX_DEPTH || out.size >= MAX_SCAN) return
        if (!node.isVisibleToUser) return
        // Ids are assigned on the way in, so the index is the collection order

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // A wrapper can report no area of its own while laying out children
        // that are perfectly visible, so this skips the node, not the subtree
        val hasArea = bounds.width() > 0 && bounds.height() > 0

        val role = roleOf(node)
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (hasArea && isWorthKeeping(node, role, text, desc)) {
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
            val child = node.getChild(i) ?: continue
            try {
                walk(child, depth + 1, out)
            } finally {
                // No-op from API 33, a real pool leak before it
                @Suppress("DEPRECATION")
                child.recycle()
            }
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
        // A game lists exactly one element, the surface it renders into.
        // Offered as a numbered entry the planner reads it as something to
        // press, and pressing it is the middle of the screen. It is never
        // addressable, so it is never listed
        val addressable = state.elements.filter { it.role != Role.SURFACE }

        if (addressable.isEmpty()) {
            append("(this screen renders its interface and exposes nothing to address")
            append(" - vision mode required, aim at the screenshot with tapAt)\n")
            return@buildString
        }

        addressable.forEach { e ->
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
            append("(the middle of this screen was too long to list - swipe to reach it)\n")
        }
    }
}
