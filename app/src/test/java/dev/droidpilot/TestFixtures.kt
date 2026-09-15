package dev.droidpilot

import android.graphics.Rect
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement

fun element(
    id: Int,
    label: String? = null,
    viewId: String? = null,
    role: Role = Role.BUTTON,
    left: Int = 0,
    top: Int = id * ROW_HEIGHT,
    right: Int = 100,
    bottom: Int = id * ROW_HEIGHT + ROW_HEIGHT,
    clickable: Boolean = true,
    scrollable: Boolean = false,
    editable: Boolean = false
) = UiElement(
    id = id,
    role = role,
    text = label,
    desc = null,
    viewId = viewId,
    bounds = Rect(left, top, right, bottom),
    clickable = clickable,
    scrollable = scrollable,
    editable = editable
)

fun screen(
    packageName: String = "com.example.app",
    activity: String? = "MainActivity",
    elements: List<UiElement> = emptyList(),
    hash: String = "hash",
    structureHash: String = hash,
    truncated: Boolean = false
) = ScreenState(
    packageName = packageName,
    activity = activity,
    elements = elements,
    screenHash = hash,
    structureHash = structureHash,
    truncated = truncated
)

private const val ROW_HEIGHT = 50
