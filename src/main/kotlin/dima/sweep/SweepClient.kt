package dima.sweep

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SweepClient {
    private const val SERVER_URL = "http://localhost:8095/completion"
    private val LOG = Logger.getInstance(SweepClient::class.java)
    private val gson = Gson()
    private val logFile = File(System.getProperty("user.home"), "sweep-plugin.log")

    fun log(message: String) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        logFile.appendText("[$ts] $message\n")
    }

    fun complete(prompt: String): String? {
        return try {
            // ~4 chars per token; reserve remaining context window for generation
            val promptTokensEstimate = prompt.length / 4
            val maxPredict = (8192 - promptTokensEstimate).coerceIn(256, 4096)

            val requestBody = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "n_predict" to maxPredict,
                    "temperature" to 0.0,
                    "stop" to listOf("<|file_sep|>", "</s>"),
                    "stream" to false
                )
            )

            log("=== REQUEST ===\n$requestBody\n")

            val connection = URL(SERVER_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            log("Response code: $responseCode")

            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                log("=== ERROR RESPONSE ===\n$errorBody\n")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            log("=== RESPONSE ===\n$responseText\n")

            val json = gson.fromJson(responseText, JsonObject::class.java)
            val content = json.get("content")?.asString

            log("Extracted content: ${content?.take(200)}")
            content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log("=== EXCEPTION ===\n${e.stackTraceToString()}\n")
            null
        }
    }
}
