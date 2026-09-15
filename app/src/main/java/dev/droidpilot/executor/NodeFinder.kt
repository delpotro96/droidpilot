package dev.droidpilot.executor

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.droidpilot.core.model.UiElement

// Resolves an observed UiElement back to a live node.
// Node references go stale as soon as the screen changes, so nothing is
// held across observations and the lookup is repeated on every action
object NodeFinder {

    fun find(root: AccessibilityNodeInfo?, target: UiElement): AccessibilityNodeInfo? {
        if (root == null) return null
        return search(root, target, 0)
    }

    private fun search(node: AccessibilityNodeInfo, target: UiElement, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null

        if (matches(node, target)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            search(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    // Bounds are the most stable identifier within one observation cycle,
    // the label only confirms the match
    private fun matches(node: AccessibilityNodeInfo, target: UiElement): Boolean {
        if (!node.isVisibleToUser) return false

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds != target.bounds) return false

        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return label == target.label
    }

    // Whether this screen offers anything to press at all. A rendered surface
    // such as a game exposes nothing, and coordinates are the only handle it
    // has ever had. Anything else has re-laid out and is not to be guessed at
    fun hasAnyActionableNode(root: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return false
        if (root.isVisibleToUser && (root.isClickable || root.isEditable || root.isCheckable)) {
            return true
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                if (hasAnyActionableNode(child, depth + 1)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    // The visible label is often a child of the node that actually handles the
    // click. Climbing without limit press a whole list row when the target was
    // an icon inside it, so the ancestor has to stay close to the size of what
    // was aimed at
    fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node

        val target = Rect().also { node.getBoundsInScreen(it) }
        val limit = area(target) * MAX_ANCESTOR_AREA_RATIO

        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) {
                val bounds = Rect().also { current!!.getBoundsInScreen(it) }
                return if (area(bounds) <= limit) current else null
            }
            current = current.parent
            hops++
        }
        return null
    }

    private fun area(bounds: Rect): Long =
        bounds.width().toLong() * bounds.height().toLong()

    private const val MAX_DEPTH = 40
    private const val MAX_ANCESTOR_HOPS = 5

    // A tappable wrapper is a little bigger than its icon, not ten times bigger
    private const val MAX_ANCESTOR_AREA_RATIO = 6
}
