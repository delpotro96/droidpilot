package dev.droidpilot.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.GridPoint
import dev.droidpilot.core.model.InstalledApp
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
    private val service: AccessibilityService,
    // Only consulted when a package name does not resolve, so a run that never
    // opens anything never walks the installed list
    private val apps: () -> List<InstalledApp> = ::emptyList
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
            is AgentAction.TapAt -> gesturePoint(action.point, state, DURATION_TAP)
            is AgentAction.LongPressAt -> gesturePoint(action.point, state, DURATION_LONG_PRESS)
            is AgentAction.Input -> input(element(action.elementId, state), action.text)
            is AgentAction.Swipe -> swipe(action.direction, action.elementId?.let { element(it, state) }, state)
            is AgentAction.Launch -> launch(action.packageName)
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

        requireGestureIsSafe(target, found = node != null)
        gestureTap(target.bounds, DURATION_TAP)
    }

    private suspend fun longPress(target: UiElement) {
        val node = NodeFinder.find(service.rootInActiveWindow, target)
        if (node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) return

        requireGestureIsSafe(target, found = node != null)
        gestureTap(target.bounds, DURATION_LONG_PRESS)
    }

    // Falling back to coordinates is what makes a game screen operable at all,
    // and useless anywhere else: the node lookup failing is precisely the
    // signal that the screen moved, and pressing the old rectangle then lands
    // on whatever replaced it.
    //
    // An earlier version looked for a node with byte-identical bounds, which a
    // changed screen almost never has, so it waved everything through. The test
    // that matters is whether this screen exposes nodes at all. One that does
    // has re-laid out and is not to be guessed at; one that does not is a
    // rendered surface where coordinates are the only handle there has ever been
    private fun requireGestureIsSafe(target: UiElement, found: Boolean) {
        // The node is still there and simply refuses the click action, as a
        // custom view or an unclickable icon inside a large row will. The
        // screen has not moved, so its coordinates are still its own
        if (found) return

        // Otherwise the lookup failed, which is the signal that the screen
        // changed. A rendered surface such as a game exposes nothing and has
        // only ever been reachable by coordinates; anything else has re-laid
        // out and must not be guessed at. A missing root tells us nothing, so
        // it is treated as the unsafe case
        val root = service.rootInActiveWindow
        check(root != null && !NodeFinder.hasAnyActionableNode(root)) {
            "the element is gone and the screen still has nodes, refusing to press " +
                target.bounds.toShortString() + " blind"
        }
    }

    // The grid the planner aims on is laid over the display, because the
    // display is what the screenshot it was shown covers.
    //
    // The size comes from the observation rather than from the display now. A
    // game turning landscape after its portrait splash changes neither hash,
    // so a point chosen against 1080 by 2340 was being scaled against 2340 by
    // 1080 and landing nowhere near what the model looked at
    private suspend fun gesturePoint(point: GridPoint, state: ScreenState, durationMs: Long) {
        val metrics = service.resources.displayMetrics
        val width = state.displayWidth.takeIf { it > 0 } ?: metrics.widthPixels
        val height = state.displayHeight.takeIf { it > 0 } ?: metrics.heightPixels
        val (x, y) = point.toPixels(width, height)

        // The policy has already refused this on any screen that lists its
        // elements, so there is nothing here to cross-check the point against.
        // The declared risk was the gate
        dispatch(Path().apply { moveTo(x, y) }, durationMs)
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

    // A package the phone does not have resolves to no intent at all, and
    // starting nothing would leave the run staring at the same screen and
    // calling it a success. The failure has to be loud enough to plan around
    private fun launch(packageName: String) {
        val resolved = resolve(packageName)
        val intent = service.packageManager.getLaunchIntentForPackage(resolved)
            ?: error("no app called " + packageName + " on this phone")

        // Started from a service, so there is no task to join
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
    }

    // A small model copying a long package name out of a list drops the tail
    // of it: com.sec.android.app.clock for com.sec.android.app.clockpackage.
    // Nothing is guessed here - the answer has to be one installed app and no
    // other, so a prefix shared by two of them is still a failure
    private fun resolve(packageName: String): String {
        val installed = apps()
        if (installed.any { it.packageName == packageName }) return packageName

        val candidates = installed.filter { it.packageName.startsWith(packageName) }
        return if (candidates.size == 1) candidates.first().packageName else packageName
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
