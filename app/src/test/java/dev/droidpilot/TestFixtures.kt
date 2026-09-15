package dev.droidpilot

import android.graphics.Rect
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement

fun element(
    id: Int,
    label: String? = null,
    role: Role = Role.BUTTON,
    left: Int = 0,
    top: Int = 0,
    right: Int = 100,
    bottom: Int = 50,
    clickable: Boolean = true,
    scrollable: Boolean = false,
    editable: Boolean = false
) = UiElement(
    id = id,
    role = role,
    text = label,
    desc = null,
    bounds = Rect(left, top, right, bottom),
    clickable = clickable,
    scrollable = scrollable,
    editable = editable
)

fun screen(
    packageName: String = "com.example.app",
    activity: String? = "MainActivity",
    elements: List<UiElement> = emptyList(),
    hash: String = "hash"
) = ScreenState(
    packageName = packageName,
    activity = activity,
    elements = elements,
    screenHash = hash
)
