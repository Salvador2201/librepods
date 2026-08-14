/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.jimena

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Groq client for Jimena: chat completions (Llama 3.3 70B) and speech-to-text (Whisper large-v3).
 * Same provider/models Salvador already uses for Jimena/YARVIS on Windows.
 */
object GroqClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val CHAT_MODEL = "llama-3.3-70b-versatile"
    const val STT_MODEL = "whisper-large-v3"

    data class ChatMessage(val role: String, val content: String)

    suspend fun chat(
        apiKey: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.6,
        maxTokens: Int = 400,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("Falta la API key de Groq"))
        try {
            val jsonMessages = JSONArray().apply {
                messages.forEach {
                    put(JSONObject().apply {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            }
            val body = JSONObject().apply {
                put("model", CHAT_MODEL)
                put("messages", jsonMessages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }
            val request = Request.Builder()
                .url(CHAT_URL)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val msg = try {
                        JSONObject(responseBody ?: "").optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    return@withContext Result.failure(Exception(msg))
                }
                val content = JSONObject(responseBody ?: "{}")
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                if (content.isNullOrBlank()) {
                    Result.failure(Exception("Respuesta vacía de Groq"))
                } else {
                    Result.success(content)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Transcribes a WAV file (16kHz mono PCM16). [languageHint] is an ISO-639-1 code, e.g. "es". */
    suspend fun transcribe(
        apiKey: String,
        wavFile: File,
        languageHint: String? = "es",
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("Falta la API key de Groq"))
        try {
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", STT_MODEL)
                .addFormDataPart(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
            if (!languageHint.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", languageHint)
            }
            val request = Request.Builder()
                .url(TRANSCRIBE_URL)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val msg = try {
                        JSONObject(responseBody ?: "").optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    return@withContext Result.failure(Exception(msg))
                }
                val text = JSONObject(responseBody ?: "{}").optString("text").trim()
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
