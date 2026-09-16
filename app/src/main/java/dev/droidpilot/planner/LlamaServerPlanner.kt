package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.InstalledApp
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
import kotlinx.serialization.json.jsonArray
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
    private val temperature: Double = 0.0,
    // Read lazily rather than passed in, because walking every installed
    // package costs more than a run that never needs to open anything
    private val apps: () -> List<InstalledApp> = ::emptyList,
    // Our own package, so the planner is never shown our interface as
    // something to operate
    private val ownPackage: String? = null
) : Planner {

    // The chat endpoint rather than /completion.
    //
    // /completion took an image_data array and a [img-N] marker in the prompt,
    // and against a current server that silently does nothing: the request
    // succeeds, the marker is left as literal text, and the model answers a
    // question about a screen it was never shown. It read as a bad model
    // rather than as a blind one. This path was checked against a real
    // screenshot and the model read the stage number off it
    private val endpoint = baseUrl.trim().trimEnd('/') + CHAT_PATH

    override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction {
        val prompt = PlannerPrompt.build(goal, state, history, apps(), ownPackage)
        val grammar =
            if (state.packageName == ownPackage) ActionGrammar.GBNF_LEAVE_ONLY
            else ActionGrammar.GBNF

        val response = runCatching { complete(prompt, state.screenshot, grammar) }.getOrElse {
            // Let cancellation unwind instead of turning it into a decision
            if (it is kotlinx.coroutines.CancellationException) throw it
            return AgentAction.Fail(unreachable(it))
        }

        return ActionParser.parse(response).getOrElse {
            // A planner that cannot be understood must not be allowed to act.
            // The reply is quoted because the two failures that cost the most
            // here both looked identical from the outside: an empty string
            // from a reasoning model, and an error page from a web server that
            // was never a planner at all
            AgentAction.Fail(
                "unreadable planner reply: " + (it.message ?: "unknown") +
                    " | " + response.take(160)
            )
        }
    }

    // A llama.cpp server speaks plain http. Asked for https it never answers,
    // because the handshake it is being offered is not one it can read, and
    // the run then sits on a TLS negotiation until the timeout. That cost
    // hours, and the message it produced named a packet header
    private fun unreachable(failure: Throwable): String {
        val detail = failure.message ?: failure.javaClass.simpleName
        val looksLikeTls = endpoint.startsWith("https://") ||
            detail.contains("TLS", ignoreCase = true) ||
            detail.contains("SSL", ignoreCase = true)

        if (looksLikeTls) {
            return "this server speaks plain http, not https - try " +
                endpoint.removeSuffix(CHAT_PATH).replaceFirst("https://", "http://")
        }
        return "planner unreachable: " + detail
    }

    // Enqueued rather than executed so cancelling the run actually aborts the
    // request. A blocking execute() would hold the coroutine until the read
    // timeout, which on a CPU-bound model is minutes
    private suspend fun complete(
        prompt: String,
        screenshot: ByteArray?,
        grammar: String
    ): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(endpoint)
            .post(payload(prompt, screenshot, grammar).toString().toRequestBody(JSON_MEDIA_TYPE))
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

    private fun payload(prompt: String, screenshot: ByteArray?, grammar: String): JsonObject = JsonObject(
        mapOf(
            // The grammar travels on the chat endpoint too, and it is what
            // keeps a small model from answering with prose, three actions at
            // once, or a risk level it invented
            "grammar" to JsonPrimitive(grammar),
            "temperature" to JsonPrimitive(temperature),
            "max_tokens" to JsonPrimitive(MAX_TOKENS),
            "stream" to JsonPrimitive(false),
            "messages" to JsonArray(listOf(userMessage(prompt, screenshot)))
        )
    )

    // The text always goes first. A vision model reads the instruction as
    // being about the image that follows it, and the goal is the instruction
    private fun userMessage(prompt: String, screenshot: ByteArray?): JsonObject {
        val parts = mutableListOf<JsonElement>(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(prompt)
                )
            )
        )

        screenshot?.let { bytes ->
            parts += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("image_url"),
                    "image_url" to JsonObject(
                        mapOf(
                            "url" to JsonPrimitive(
                                DATA_URL_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            )
                        )
                    )
                )
            )
        }

        return JsonObject(
            mapOf(
                "role" to JsonPrimitive("user"),
                "content" to JsonArray(parts)
            )
        )
    }

    private fun readContent(response: Response): String {
        val body = response.body?.string().orEmpty()
        check(response.isSuccessful) { "server returned " + response.code }

        val message = JSON.parseToJsonElement(body)
            .jsonObject["choices"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject
            ?: error("response had no message")

        // A reasoning model puts its answer in reasoning_content and leaves
        // content empty, and a grammar does not change that - it only decides
        // what the thinking is allowed to look like. Reading one field gave a
        // blank string and an unreadable planner, on a model that had in fact
        // answered correctly
        return listOf("content", "reasoning_content")
            .firstNotNullOfOrNull { field ->
                message[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
            ?: error("response had no message content")
    }

    companion object {
        private const val CHAT_PATH = "/v1/chat/completions"
        private const val MAX_TOKENS = 128
        private const val DATA_URL_PREFIX = "data:image/png;base64,"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }

        // A screenshot through a vision encoder is the slow part, and on a
        // small card it runs to the better part of a minute. Measured at just
        // under a minute for a 1568px image on a 4GB GPU, so the old two
        // minute ceiling was cutting runs off mid-thought
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
