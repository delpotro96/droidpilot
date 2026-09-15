package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
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
            "tap" -> AgentAction.Tap(obj.requireInt("elementId"))
            "longPress" -> AgentAction.LongPress(obj.requireInt("elementId"))
            "input" -> AgentAction.Input(obj.requireInt("elementId"), obj.requireString("text"))
            "swipe" -> AgentAction.Swipe(direction(obj.requireString("direction")))
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait(obj.long("millis") ?: DEFAULT_WAIT_MILLIS)
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

    private fun JsonObject.requireInt(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: error("missing or non-numeric field: " + key)

    private fun JsonObject.requireString(key: String): String =
        string(key) ?: error("missing field: " + key)

    private const val DEFAULT_WAIT_MILLIS = 1000L
}
