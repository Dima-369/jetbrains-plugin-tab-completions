package dima.sweep

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LlmClient {
    private const val CHAT_URL = "http://localhost:8095/v1/chat/completions"
    private val gson = Gson()
    private val logFile = File(System.getProperty("user.home"), "sweep-plugin.log")

    fun log(message: String) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        logFile.appendText("[$ts] $message\n")
    }

    fun chatComplete(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 256,
        temperature: Double = 0.0,
        stop: List<String> = emptyList(),
    ): String? {
        return try {
            val requestBody = gson.toJson(
                mapOf(
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt),
                    ),
                    "max_tokens" to maxTokens,
                    "temperature" to temperature,
                    "stop" to stop,
                    "stream" to false
                )
            )

            log("=== CHAT REQUEST ===\n$requestBody\n")

            val connection = URL(CHAT_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            log("Chat response code: $responseCode")

            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                log("=== CHAT ERROR ===\n$errorBody\n")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            log("=== CHAT RESPONSE ===\n$responseText\n")

            val json = gson.fromJson(responseText, JsonObject::class.java)
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            log("Chat extracted: ${content?.take(200)}")
            content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log("=== CHAT EXCEPTION ===\n${e.stackTraceToString()}\n")
            null
        }
    }
}
