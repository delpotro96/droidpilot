package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
import dev.droidpilot.core.model.GridPoint
import dev.droidpilot.core.model.Risk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Turns a model response into an action. The grammar should make this
// infallible, but a server without grammar support or a truncated response
// still has to fail loudly rather than produce a wrong tap
object ActionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Result<AgentAction> = runCatching {
        val body = extractObject(raw) ?: error("no JSON object in response: " + raw.take(120))
        val obj = json.parseToJsonElement(body).jsonObject

        when (val name = obj.string("action") ?: error("missing action field")) {
            "tap" -> AgentAction.Tap(obj.requireInt("elementId"), obj.risk())
            "longPress" -> AgentAction.LongPress(obj.requireInt("elementId"), obj.risk())
            "tapAt" -> AgentAction.TapAt(obj.requirePoint(), obj.risk())
            "longPressAt" -> AgentAction.LongPressAt(obj.requirePoint(), obj.risk())
            "input" -> AgentAction.Input(obj.requireInt("elementId"), obj.requireString("text"))
            "swipe" -> AgentAction.Swipe(
                direction(obj.requireString("direction")),
                obj.int("elementId"),
                obj.risk()
            )
            "launch" -> AgentAction.Launch(obj.requireString("package").trim())
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait(
                (obj.long("millis") ?: DEFAULT_WAIT_MILLIS).coerceIn(0L, MAX_WAIT_MILLIS)
            )
            "ask" -> AgentAction.AskUser(obj.requireString("question"))
            "done" -> AgentAction.Done(obj.requireString("summary"))
            "fail" -> AgentAction.Fail(obj.requireString("reason"))
            else -> error("unknown action: " + name)
        }
    }

    // Some servers wrap the answer in prose or a markdown fence even with a
    // grammar in place, so the first balanced object is taken
    private fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun direction(value: String): Direction =
        when (value.lowercase()) {
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            else -> error("unknown direction: " + value)
        }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull

    // A server without grammar support may omit it. Silence is not a promise
    // that the action is safe, so the keyword net decides in that case
    private fun JsonObject.risk(): Risk = when (string("risk")?.lowercase()) {
        "spends" -> Risk.SPENDS
        "irreversible" -> Risk.IRREVERSIBLE
        else -> Risk.NONE
    }

    private fun JsonObject.requireInt(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: error("missing or non-numeric field: " + key)

    // A model that overshoots the grid meant the edge, not a press off screen.
    // Clamping keeps a rounding error from becoming a negative coordinate the
    // gesture dispatcher would silently drop
    private fun JsonObject.requirePoint(): GridPoint =
        GridPoint.clamped(requireInt("x"), requireInt("y"))

    private fun JsonObject.requireString(key: String): String =
        string(key) ?: error("missing field: " + key)

    private const val DEFAULT_WAIT_MILLIS = 1000L

    // Waiting is for a screen to settle. Anything longer is the planner
    // stalling, and the step budget counts steps rather than time
    private const val MAX_WAIT_MILLIS = 15_000L
}
