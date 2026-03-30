package com.llamatik.teammate.llamatikteammate

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LlamatikClient {

    private val endpoint = "http://localhost:8080/generate"

    fun generate(prompt: String, callback: (String) -> Unit) {
        thread {
            try {
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val body = """
                    {
                        "prompt": ${escape(prompt)},
                        "max_tokens": 512
                    }
                """.trimIndent()

                connection.outputStream.use {
                    it.write(body.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().readText()
                callback(response)

            } catch (e: Exception) {
                callback("Error: ${e.message}")
            }
        }
    }

    private fun escape(text: String): String {
        return "\"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
