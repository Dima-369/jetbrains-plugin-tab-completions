package dima.sweep

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FimClient {
    private const val SERVER_URL = "http://localhost:8095/completion"
    const val DEFAULT_N_PREDICT = 64
    private val gson = Gson()
    private val logFile = File(System.getProperty("user.home"), "sweep-plugin.log")

    fun log(message: String) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        logFile.appendText("[$ts] $message\n")
    }

    fun complete(
        prefix: String,
        suffix: String,
        nPredict: Int = DEFAULT_N_PREDICT,
        temperature: Double = 0.0,
    ): String? {
        return try {
            val prompt = "<|fim_prefix|>$prefix<|fim_suffix|>$suffix<|fim_middle|>"

            val requestBody = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "n_predict" to nPredict,
                    "temperature" to temperature,
                    "stop" to listOf(
                        "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>", "<|fim_pad|>",
                        "<|endoftext|>", "<|file_sep|>", "</s>",
                    ),
                    "stream" to false
                )
            )

            log("=== FIM REQUEST ===\n$requestBody\n")

            val connection = URL(SERVER_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            log("FIM response code: $responseCode")

            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                log("=== FIM ERROR ===\n$errorBody\n")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            log("=== FIM RESPONSE ===\n$responseText\n")

            val json = gson.fromJson(responseText, JsonObject::class.java)
            val content = json.get("content")?.asString

            log("FIM extracted: ${content?.take(200)}")
            content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log("=== FIM EXCEPTION ===\n${e.stackTraceToString()}\n")
            null
        }
    }
}
