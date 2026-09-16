package dev.droidpilot.observer

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.serializer.ScreenSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

// The eyes and hands of the agent. Nothing works until this service is enabled
class AgentAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastActivity: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only screen transitions are tracked, everything else is dropped
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastActivity = event.className?.toString()
        }
    }

    override fun onInterrupt() = Unit

    // Walking the tree is a recursive series of binder calls, so it stays off
    // whatever thread the caller happens to be on
    suspend fun observe(): ScreenState = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow
        val metrics = resources.displayMetrics
        val state = ScreenSerializer.serialize(
            root = root,
            packageName = root?.packageName?.toString() ?: "unknown",
            activity = lastActivity,
            displayHeight = metrics.heightPixels
        ).copy(
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels
        )

        // The listing cannot describe this screen, which is what a game looks
        // like. A screenshot is the only remaining description
        if (state.isTextUsable || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            state
        } else {
            // A secured capture comes back successful and flat. Keeping it
            // would put a black rectangle in front of the planner, which
            // answers with invented coordinates rather than admitting it
            // cannot see. Discarding it leaves the screen undescribed, and
            // the loop refuses to act on a screen it cannot describe
            val shot = captureUsable()
            if (shot == null) Log.w(TAG, "no usable capture of " + state.packageName)
            state.copy(screenshot = shot)
        }
    }

    // The platform allows one capture a second and rejects anything sooner,
    // and the loop observes twice per step. Without the second attempt an
    // ordinary rate limit would read exactly like a secured screen, and the
    // run would stop on a game it could perfectly well see
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureUsable(): ByteArray? {
        captureScreenshot()?.takeUnless { ScreenshotProbe.isBlank(it) }?.let { return it }

        delay(RATE_LIMIT_MILLIS)
        return captureScreenshot()?.takeUnless { ScreenshotProbe.isBlank(it) }
    }

    // Only called when the text listing cannot describe the screen, such as in games
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshot(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bytes = runCatching {
                        result.hardwareBuffer.use { buffer ->
                            Bitmap.wrapHardwareBuffer(
                                buffer,
                                result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                            )?.let { encode(it) }
                        }
                    }.getOrNull()
                    cont.resume(bytes)
                }

                override fun onFailure(errorCode: Int) {
                    // The platform rejects back-to-back requests, one per second by default
                    Log.w(TAG, "screenshot failed, code=" + errorCode)
                    cont.resume(null)
                }
            }
        )
    }

    // Vision token cost scales with resolution, so shrink the long edge before sending
    private fun encode(src: Bitmap): ByteArray {
        val longest = maxOf(src.width, src.height)
        val bitmap = if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                src.copy(Bitmap.Config.ARGB_8888, false),
                (src.width * scale).toInt(),
                (src.height * scale).toInt(),
                true
            )
        } else {
            src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "DroidPilot"
        // Measured against a real game screen on a 4GB card: 1568px took 56
        // seconds a step, 1024 took 20, 784 took 10. Below 1024 the model
        // stopped reading the buttons and started inventing them - it named
        // the countdown timer as a button - so this is the knee rather than
        // the floor. A forty step run is thirteen minutes at this size
        private const val MAX_EDGE = 1024

        // The platform's own limit on how often a capture may be requested
        private const val RATE_LIMIT_MILLIS = 1100L

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
