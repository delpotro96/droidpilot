package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.Planner
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Talks to a llama.cpp server, whether that runs on this phone, on a machine
// at home, or anywhere else reachable. Only the base URL changes
class LlamaServerPlanner(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val temperature: Double = 0.0
) : Planner {

    private val endpoint = baseUrl.trim().trimEnd('/') + "/completion"

    override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction {
        val prompt = PlannerPrompt.build(goal, state, history)

        val response = runCatching { complete(prompt, state.screenshot) }.getOrElse {
            // Let cancellation unwind instead of turning it into a decision
            if (it is kotlinx.coroutines.CancellationException) throw it
            return AgentAction.Fail("planner unreachable: " + (it.message ?: it.javaClass.simpleName))
        }

        return ActionParser.parse(response).getOrElse {
            // A planner that cannot be understood must not be allowed to act
            AgentAction.Fail("unreadable planner response: " + (it.message ?: "unknown"))
        }
    }

    // Enqueued rather than executed so cancelling the run actually aborts the
    // request. A blocking execute() would hold the coroutine until the read
    // timeout, which on a CPU-bound model is minutes
    private suspend fun complete(
        prompt: String,
        screenshot: ByteArray?
    ): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(endpoint)
            .post(payload(prompt, screenshot).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching { readContent(it) }
                    if (!cont.isActive) return
                    result
                        .onSuccess { content -> cont.resume(content) }
                        .onFailure { failure -> cont.resumeWithException(failure) }
                }
            }
        })
    }

    private fun payload(prompt: String, screenshot: ByteArray?): JsonObject {
        val fields = mutableMapOf<String, JsonElement>(
            "grammar" to JsonPrimitive(ActionGrammar.GBNF),
            "temperature" to JsonPrimitive(temperature),
            "n_predict" to JsonPrimitive(MAX_TOKENS),
            "stream" to JsonPrimitive(false)
        )

        if (screenshot == null) {
            fields["prompt"] = JsonPrimitive(prompt)
            // Reusing the cached prefix only pays off while the prompt stays text
            fields["cache_prompt"] = JsonPrimitive(true)
            return JsonObject(fields)
        }

        // llama.cpp substitutes the marker with the encoded image. A multimodal
        // model has to be loaded server side for this to mean anything
        fields["prompt"] = JsonPrimitive("[img-1]\n" + prompt)
        fields["image_data"] = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1),
                        "data" to JsonPrimitive(
                            Base64.encodeToString(screenshot, Base64.NO_WRAP)
                        )
                    )
                )
            )
        )
        return JsonObject(fields)
    }

    private fun readContent(response: Response): String {
        val body = response.body?.string().orEmpty()
        check(response.isSuccessful) { "server returned " + response.code }

        return JSON.parseToJsonElement(body)
            .jsonObject["content"]
            ?.jsonPrimitive?.contentOrNull
            ?: error("response had no content field")
    }

    companion object {
        private const val MAX_TOKENS = 128
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }

        // A phone on wifi and a model thinking on CPU both need room
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
