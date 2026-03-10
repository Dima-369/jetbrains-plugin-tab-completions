package dima.sweep

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL

object SweepClient {
    private const val SERVER_URL = "http://localhost:8095/completion"
    private val LOG = Logger.getInstance(SweepClient::class.java)
    private val gson = Gson()

    fun complete(prompt: String): String? {
        return try {
            val requestBody = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "n_predict" to 512,
                    "temperature" to 0.0,
                    "stop" to listOf("<|file_sep|>", "</s>"),
                    "stream" to false
                )
            )

            val connection = URL(SERVER_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            if (connection.responseCode != 200) {
                LOG.warn("Sweep server returned ${connection.responseCode}")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val json = gson.fromJson(responseText, JsonObject::class.java)
            val content = json.get("content")?.asString

            content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.warn("Failed to call sweep server: ${e.message}")
            null
        }
    }
}
