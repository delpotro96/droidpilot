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
        val state = ScreenSerializer.serialize(
            root = root,
            packageName = root?.packageName?.toString() ?: "unknown",
            activity = lastActivity
        )

        // The listing cannot describe this screen, which is what a game looks
        // like. A screenshot is the only remaining description
        if (state.isTextUsable || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            state
        } else {
            state.copy(screenshot = captureScreenshot())
        }
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
        private const val MAX_EDGE = 1568

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
