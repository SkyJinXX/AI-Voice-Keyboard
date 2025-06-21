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

package com.example.whispertoinput.recorder

import android.media.MediaRecorder
import android.util.Log
import java.io.File

class RecorderManager {
    companion object {
        private const val TAG = "RecorderManager"
        private const val MIN_RECORDING_DURATION_MS = 1000L // Minimum 1 second recording
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var outputFile: String? = null

    fun start(filename: String): Boolean {
        Log.d(TAG, "=== Starting recording ===")
        Log.d(TAG, "Target filename: $filename")
        
        try {
            // Clean up any existing recorder
            stop()
            
            outputFile = filename
            recordingStartTime = System.currentTimeMillis()
            
            val file = File(filename)
            if (file.exists()) {
                Log.w(TAG, "File already exists, deleting: ${file.length()} bytes")
                file.delete()
            }
            
            mediaRecorder = MediaRecorder().apply {
                Log.d(TAG, "Configuring MediaRecorder")
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(filename)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                Log.d(TAG, "Preparing MediaRecorder")
                prepare()
                Log.d(TAG, "MediaRecorder prepared successfully")

                Log.d(TAG, "Starting MediaRecorder")
                start()
                Log.d(TAG, "MediaRecorder started successfully")
            }
            
            isRecording = true
            startAmplitudeMonitoring()
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            cleanup()
            return false
        }
    }

    fun stop(): Boolean {
        Log.d(TAG, "=== Stopping recording ===")
        
        if (!isRecording) {
            Log.d(TAG, "Recording stopped completely")
            return true
        }

        try {
            val recordingDuration = System.currentTimeMillis() - recordingStartTime
            Log.d(TAG, "Recording duration: ${recordingDuration}ms")
            
            // Check if recording duration is too short
            if (recordingDuration < MIN_RECORDING_DURATION_MS) {
                Log.w(TAG, "Recording too short (${recordingDuration}ms), minimum is ${MIN_RECORDING_DURATION_MS}ms")
                
                // Add a delay to reach minimum duration if needed
                val remainingTime = MIN_RECORDING_DURATION_MS - recordingDuration
                if (remainingTime > 0) {
                    Log.d(TAG, "Waiting ${remainingTime}ms to reach minimum recording duration")
                    try {
                        Thread.sleep(remainingTime)
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "Sleep interrupted while waiting for minimum duration", e)
                    }
                }
                
                // Check if file might still be corrupted
                if (recordingDuration < 500) {  // If extremely short, still consider it potentially corrupted
                    Log.w(TAG, "Recording extremely short, may still be corrupted")
                    Log.w(TAG, "Deleting potentially corrupted file")
                
                // Clean up the potentially corrupted short recording
                cleanup()
                
                // Delete the file since it's likely corrupted
                outputFile?.let { filename ->
                    val file = File(filename)
                    if (file.exists()) {
                        Log.w(TAG, "Deleting short recording file: ${file.length()} bytes")
                        file.delete()
                    }
                }
                
                Log.e(TAG, "Recording failed: Duration too short")
                return false
                }
            }

            mediaRecorder?.let { recorder ->
                try {
                    Log.d(TAG, "Stopping MediaRecorder")
                    recorder.stop()
                    Log.d(TAG, "Releasing MediaRecorder")
                    recorder.release()
                    Log.d(TAG, "MediaRecorder stopped and released successfully")
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Error stopping MediaRecorder: ${e.message}")
                    
                    // If stop fails, try to release anyway
                    try {
                        recorder.release()
                        Log.d(TAG, "MediaRecorder released after stop failure")
                    } catch (releaseException: Exception) {
                        Log.e(TAG, "Error releasing MediaRecorder after stop failure", releaseException)
                    }
                    
                    // Check if the file exists and is valid
                    outputFile?.let { filename ->
                        val file = File(filename)
                        if (file.exists() && file.length() > 0) {
                            Log.w(TAG, "File exists despite stop failure, size: ${file.length()} bytes")
                            // Continue with cleanup but don't return false
                        } else {
                            Log.e(TAG, "No valid file created due to stop failure")
                            cleanup()
                            return false
                        }
                    }
                }
            }

            isRecording = false
            mediaRecorder = null
            Log.d(TAG, "Recording stopped completely")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error stopping recording", e)
            cleanup()
            return false
        }
    }
    
    private fun cleanup() {
        try {
            mediaRecorder?.let { recorder ->
                try {
                    if (isRecording) {
                        recorder.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping recorder during cleanup", e)
                }
                
                try {
                    recorder.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing recorder during cleanup", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    // ... existing code ...
    
    private fun startAmplitudeMonitoring() {
        // Start amplitude monitoring in a separate thread
        Thread {
            var count = 0
            while (isRecording) {
                try {
                    Thread.sleep(150) // Check every 150ms
                    if (isRecording) {
                        count++
                        if (count % 10 == 0) { // Log every 1.5 seconds (10 * 150ms)
                            mediaRecorder?.let { recorder ->
                                try {
                                    val amplitude = recorder.maxAmplitude
                                    Log.d(TAG, "Amplitude report #$count: $amplitude")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error getting amplitude", e)
                                }
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Amplitude monitoring interrupted")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error in amplitude monitoring", e)
                }
            }
            Log.d(TAG, "Amplitude monitoring stopped")
        }.start()
    }
}