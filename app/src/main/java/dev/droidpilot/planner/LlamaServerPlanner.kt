package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.Planner
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Talks to a llama.cpp server, whether that runs on this phone, on a machine
// at home, or anywhere else reachable. Only the base URL changes
class LlamaServerPlanner(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val temperature: Double = 0.0
) : Planner {

    override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction {
        val prompt = PlannerPrompt.build(goal, state, history)
        val response = complete(prompt)

        return ActionParser.parse(response).getOrElse {
            // A planner that cannot be understood must not be allowed to act
            AgentAction.Fail("unreadable planner response: " + (it.message ?: "unknown"))
        }
    }

    private suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        val payload = JsonObject(
            mapOf(
                "prompt" to JsonPrimitive(prompt),
                "grammar" to JsonPrimitive(ActionGrammar.GBNF),
                "temperature" to JsonPrimitive(temperature),
                "n_predict" to JsonPrimitive(MAX_TOKENS),
                "cache_prompt" to JsonPrimitive(true),
                "stream" to JsonPrimitive(false)
            )
        )

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/completion")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "planner server returned " + response.code }

            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(body)
                .jsonObject["content"]
                ?.jsonPrimitive?.contentOrNull
                ?: error("planner response had no content field")
        }
    }

    companion object {
        private const val MAX_TOKENS = 128
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // A phone on wifi and a model thinking on CPU both need room
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
