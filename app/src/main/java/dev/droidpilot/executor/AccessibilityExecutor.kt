package dev.droidpilot.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Applies a decided action to the live screen.
// The accessibility action is tried first and falls back to a coordinate
// gesture. Game screens expose no actionable nodes at all, so the gesture
// path is the only one that reaches them
class AccessibilityExecutor(
    private val service: AccessibilityService
) : Executor {

    override suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit> = try {
        Result.success(apply(action, state))
    } catch (cancelled: CancellationException) {
        // Cancelling a run is not an action failing. Reporting it as one made
        // the loop record a divergence against a path that was never at fault
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private suspend fun apply(action: AgentAction, state: ScreenState) {
        when (action) {
            is AgentAction.Tap -> tap(element(action.elementId, state))
            is AgentAction.LongPress -> longPress(element(action.elementId, state))
            is AgentAction.Input -> input(element(action.elementId, state), action.text)
            is AgentAction.Swipe -> swipe(action.direction, action.elementId?.let { element(it, state) }, state)
            AgentAction.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            AgentAction.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            is AgentAction.Wait -> delay(action.millis)

            // Terminal actions handled by the loop, nothing to apply here
            is AgentAction.AskUser,
            is AgentAction.Done,
            is AgentAction.Fail -> Unit
        }
    }

    private fun element(id: Int, state: ScreenState): UiElement =
        state.elements.getOrNull(id)
            ?: error("no element " + id + ", screen has " + state.elements.size)

    private suspend fun tap(target: UiElement) {
        val node = NodeFinder.find(service.rootInActiveWindow, target)
        val clickable = node?.let { NodeFinder.clickableSelfOrAncestor(it) }

        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return

        requireCoordinatesStillFree(target)
        gestureTap(target.bounds, DURATION_TAP)
    }

    private suspend fun longPress(target: UiElement) {
        val node = NodeFinder.find(service.rootInActiveWindow, target)
        if (node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) return

        requireCoordinatesStillFree(target)
        gestureTap(target.bounds, DURATION_LONG_PRESS)
    }

    // Falling back to coordinates is what makes a game screen operable at all,
    // but the same fallback pressed whatever had re-laid out into the old
    // rectangle. The node lookup failing is precisely the signal that the
    // screen moved, so before touching the pixels: if something else is sitting
    // there now, refuse. Only genuinely empty space is fair game
    private fun requireCoordinatesStillFree(target: UiElement) {
        val occupant = NodeFinder.occupantAt(service.rootInActiveWindow, target.bounds) ?: return

        val label = occupant.text?.toString()?.takeIf { it.isNotBlank() }
            ?: occupant.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        check(label == target.label) {
            "another element took those coordinates: " + (label ?: "unlabelled")
        }
    }

    private fun input(target: UiElement, text: String) {
        val node = NodeFinder.find(service.rootInActiveWindow, target)
            ?: error("input target node not found")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "set text rejected" }
    }

    private suspend fun swipe(direction: Direction, target: UiElement?, state: ScreenState) {
        val area = target?.bounds ?: state.elements.firstOrNull { it.scrollable }?.bounds ?: fullScreen(state)
        val cx = area.centerX().toFloat()
        val cy = area.centerY().toFloat()
        val dx = area.width() * SWIPE_RATIO / 2
        val dy = area.height() * SWIPE_RATIO / 2

        // Scrolling down means the finger travels up
        val (ex, ey) = when (direction) {
            Direction.DOWN -> cx to cy - dy
            Direction.UP -> cx to cy + dy
            Direction.LEFT -> cx + dx to cy
            Direction.RIGHT -> cx - dx to cy
        }

        dispatch(Path().apply { moveTo(cx, cy); lineTo(ex, ey) }, DURATION_SWIPE)
    }

    private fun global(actionId: Int) {
        check(service.performGlobalAction(actionId)) { "global action " + actionId + " failed" }
    }

    private suspend fun gestureTap(bounds: Rect, durationMs: Long) {
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        dispatch(path, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val completed = suspendCancellableCoroutine { cont ->
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }
        check(completed) { "gesture failed" }
    }

    // Swiping needs somewhere to swipe. An empty tree yields an empty union,
    // which would dispatch a zero length gesture that silently does nothing,
    // so the display is used instead
    private fun fullScreen(state: ScreenState): Rect {
        val union = state.elements.fold(Rect()) { acc, e -> acc.apply { union(e.bounds) } }
        if (!union.isEmpty) return union

        val metrics = service.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private companion object {
        const val DURATION_TAP = 60L
        const val DURATION_LONG_PRESS = 700L
        const val DURATION_SWIPE = 300L
        const val SWIPE_RATIO = 0.6f
    }
}
