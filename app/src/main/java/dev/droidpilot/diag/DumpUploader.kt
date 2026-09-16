package dev.droidpilot.diag

import dev.droidpilot.core.model.ScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Posts an observation to a listener on the development machine.
//
// Reading a screen off the phone meant copying an abbreviated listing by hand,
// which dropped the coordinates every policy decision is made on, and dropped
// the screenshot entirely. The phone reaches the machine over the tailnet on
// mobile data, so this works without the phone being on any particular network
class DumpUploader(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient()
) {

    sealed interface Result {
        data class Sent(val bytes: Int) : Result
        data class Failed(val reason: String) : Result
    }

    // A run that stalls is the one whose log matters most, and it is also the
    // one nobody can read: the log lives on the phone, and the phone is in
    // another app by design
    suspend fun sendLog(goal: String, lines: List<String>): Result =
        post(PATH_LOG, json.encodeToString(RunLog(goal, lines, System.currentTimeMillis())))

    suspend fun send(state: ScreenState): Result = withContext(Dispatchers.IO) {
        val body = runCatching { json.encodeToString(ScreenDump.of(state)) }
            .getOrElse { return@withContext Result.Failed("could not encode: " + describe(it)) }
        post(PATH, body)
    }

    private suspend fun post(path: String, body: String): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + path)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Sent(body.length)
                } else {
                    Result.Failed("listener answered " + response.code)
                }
            }
        }.getOrElse { Result.Failed(describe(it)) }
    }

    private fun describe(failure: Throwable): String =
        failure.message ?: failure.javaClass.simpleName

    private companion object {
        const val PATH = "/dump"
        const val PATH_LOG = "/log"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // A screenshot of a phone display runs to a few hundred kilobytes, and
        // the tailnet adds a hop, so the write timeout is the generous one
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val json = Json { encodeDefaults = true }
    }
}
