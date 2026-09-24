package com.theunixroot.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object CloudflareSttService {

    suspend fun transcribeAudio(
        accountId: String,
        apiToken: String,
        audioBytes: ByteArray,
        model: String = "@cf/openai/whisper"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", "STT-Android-App")
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(audioBytes)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            val responseStream: InputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = responseStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(1024)
                var nRead: Int
                while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
                    buffer.write(data, 0, nRead)
                }
                buffer.toString("UTF-8")
            }

            if (responseCode in 200..299) {
                val json = JSONObject(responseBody)
                val success = json.optBoolean("success", true)
                if (success) {
                    val result = json.optJSONObject("result")
                    val text = result?.optString("text", "") ?: ""
                    Result.success(text.trim())
                } else {
                    val errors = json.optJSONArray("errors")
                    val errMsg = if (errors != null && errors.length() > 0) {
                        errors.getJSONObject(0).optString("message", "Cloudflare error")
                    } else {
                        "Cloudflare error: $responseBody"
                    }
                    Result.failure(Exception(errMsg))
                }
            } else {
                Result.failure(Exception("HTTP $responseCode: $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
