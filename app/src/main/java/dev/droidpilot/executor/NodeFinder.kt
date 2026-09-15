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

    // The visible label is often a child of the node that actually handles the click
    fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private const val MAX_DEPTH = 40
    private const val MAX_ANCESTOR_HOPS = 5
}
