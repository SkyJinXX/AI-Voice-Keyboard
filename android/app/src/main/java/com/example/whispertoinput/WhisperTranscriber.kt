/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2024 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperTranscriber {
    private data class Config(
        val endpoint: String,
        val languageCode: String,
        val isRequestStyleOpenaiApi: Boolean,
        val apiKey: String,
        val prompt: String
    )

    private val TAG = "WhisperTranscriber"
    private var currentTranscriptionJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        suspend fun makeWhisperRequest(): String {
            Log.d(TAG, "=== Starting transcription request ===")
            Log.d(TAG, "Audio file: $filename")
            
            // Check if audio file exists and get its size
            val audioFile = File(filename)
            if (!audioFile.exists()) {
                Log.e(TAG, "Audio file does not exist: $filename")
                throw Exception("Audio file not found: $filename")
            }
            
            val fileSize = audioFile.length()
            Log.d(TAG, "Audio file size: $fileSize bytes")
            
            if (fileSize == 0L) {
                Log.e(TAG, "Audio file is empty")
                throw Exception("Audio file is empty")
            }
            
            // Retrieve configs
            val (endpoint, languageCode, isRequestStyleOpenaiApi, apiKey, prompt) = context.dataStore.data.map { preferences: Preferences ->
                Config(
                    preferences[ENDPOINT] ?: "",
                    preferences[LANGUAGE_CODE] ?: "auto",
                    preferences[REQUEST_STYLE] ?: true,
                    preferences[API_KEY] ?: "",
                    preferences[PROMPT] ?: ""
                )
            }.first()

            Log.d(TAG, "Config - Endpoint: $endpoint")
            Log.d(TAG, "Config - Language: $languageCode")
            Log.d(TAG, "Config - OpenAI API: $isRequestStyleOpenaiApi")
            Log.d(TAG, "Config - Prompt: ${if (prompt.isEmpty()) "(empty)" else "\"$prompt\""}")

            // Foolproof message
            if (endpoint == "") {
                throw Exception(context.getString(R.string.error_endpoint_unset))
            }

            // Make request
            val client = OkHttpClient.Builder()
                .connectTimeout(context.resources.getInteger(R.integer.network_connect_timeout).toLong(), TimeUnit.SECONDS)
                .writeTimeout(context.resources.getInteger(R.integer.network_write_timeout).toLong(), TimeUnit.SECONDS)
                .readTimeout(context.resources.getInteger(R.integer.network_read_timeout).toLong(), TimeUnit.SECONDS)
                .callTimeout(context.resources.getInteger(R.integer.network_call_timeout).toLong(), TimeUnit.SECONDS)
                .build()
            val finalUrl = if (isRequestStyleOpenaiApi) endpoint else "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
            Log.d(TAG, "Final URL: $finalUrl")
            
            val request = buildWhisperRequest(
                context,
                filename,
                finalUrl,
                mediaType,
                languageCode,
                apiKey,
                prompt,
                isRequestStyleOpenaiApi
            )
            
            Log.d(TAG, "Making HTTP request...")
            val response = client.newCall(request).execute()
            
            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Response message: ${response.message}")

            // If request is not successful, or response code is weird
            if (!response.isSuccessful || response.code / 100 != 2) {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "Request failed - Code: ${response.code}, Error: $errorBody")
                throw Exception(errorBody.replace('\n', ' '))
            }

            val responseText = response.body!!.string().trim()
            Log.d(TAG, "Response length: ${responseText.length} characters")
            Log.d(TAG, "Response preview: ${responseText.take(100)}")
            
            return responseText + attachToEnd
        }

        // Create a cancellable job in the main thread (for UI updating)
        val job = CoroutineScope(Dispatchers.Main).launch {

            // Within the job, make a suspend call at the I/O thread
            // It suspends before result is obtained.
            // Returns (transcribed string, exception message)
            val (transcribedText, exceptionMessage) = withContext(Dispatchers.IO) {
                try {
                    // Perform transcription here
                    val response = makeWhisperRequest()
                    // Clean up unused audio file after transcription
                    // Ref: https://developer.android.com/reference/android/media/MediaRecorder#setOutputFile(java.io.File)
                    val deleted = File(filename).delete()
                    Log.d(TAG, "Audio file deleted after transcription: $deleted")
                    return@withContext Pair(response, null)
                } catch (e: CancellationException) {
                    // Task was canceled
                    Log.d(TAG, "Transcription task was canceled")
                    return@withContext Pair(null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription failed: ${e.message}", e)
                    return@withContext Pair(null, e.message)
                }
            }

            // This callback is within the main thread.
            callback.invoke(transcribedText)

            // If exception message is not null
            if (!exceptionMessage.isNullOrEmpty()) {
                Log.e(TAG, exceptionMessage)
                exceptionCallback(exceptionMessage)
            }
        }

        registerTranscriptionJob(job)
    }

    fun stop() {
        registerTranscriptionJob(null)
    }

    private fun registerTranscriptionJob(job: Job?) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = job
    }

    private fun buildWhisperRequest(
        context: Context,
        filename: String,
        url: String,
        mediaType: String,
        languageCode: String,
        apiKey: String,
        prompt: String,
        isRequestStyleOpenaiApi: Boolean
    ): Request {
        // Please refer to the following for the endpoint/payload definitions:
        // - https://ahmetoner.com/whisper-asr-webservice/run/#usage
        // - https://platform.openai.com/docs/api-reference/audio/createTranscription
        // - https://platform.openai.com/docs/api-reference/making-requests
        val file: File = File(filename)
        Log.d(TAG, "Building request for file: $filename")
        Log.d(TAG, "File exists: ${file.exists()}, size: ${file.length()} bytes")
        Log.d(TAG, "Media type: $mediaType")
        
        if (file.exists() && file.length() > 0) {
            try {
                val fileBytes = file.readBytes()
                Log.d(TAG, "File read successfully: ${fileBytes.size} bytes")
                Log.d(TAG, "First few bytes: ${fileBytes.take(20).joinToString { String.format("%02x", it) }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read file for debugging: ${e.message}")
            }
        }
        
        val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
        val requestBody: RequestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            
            if (isRequestStyleOpenaiApi) {
                addFormDataPart("file", "@audio.m4a", fileBody)
                addFormDataPart("model", "gpt-4o-mini-transcribe")
                addFormDataPart("response_format", "text")
                
                // Add language if specified (not auto)
                if (languageCode != "auto" && languageCode.isNotEmpty()) {
                    addFormDataPart("language", languageCode)
                }
                
                // Add prompt if specified
                if (prompt.isNotEmpty()) {
                    addFormDataPart("prompt", prompt)
                }
            } else {
                // For Whisper Webservice (legacy support)
                addFormDataPart("audio_file", "@audio.m4a", fileBody)
            }
        }.build()

        val requestHeaders: Headers = Headers.Builder().apply {
            if (isRequestStyleOpenaiApi) {
                // Foolproof message
                if (apiKey == "") {
                    throw Exception(context.getString(R.string.error_apikey_unset))
                }
                add("Authorization", "Bearer $apiKey")
            }
            add("Content-Type", "multipart/form-data")
        }.build()

        return Request.Builder()
            .headers(requestHeaders)
            .url(url)
            .post(requestBody)
            .build()
    }
}
