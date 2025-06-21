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

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import android.content.Context
import androidx.datastore.core.DataStore
import com.example.whispertoinput.dataStore
import com.example.whispertoinput.AUTO_RECORDING_START

private const val RECORDED_AUDIO_FILENAME = "recorded.m4a"
private const val AUDIO_MEDIA_TYPE = "audio/mp4"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    companion object {
        private const val TAG = "WhisperInputService"
    }
    
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var recorderManager: RecorderManager
    private var recordedAudioFilename: String = ""
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(text: String?) {
        Log.d(TAG, "=== Transcription completed ===")
        Log.d(TAG, "Result text: ${text ?: "(null)"}")
        if (!text.isNullOrEmpty()) {
            Log.d(TAG, "Committing text to input connection")
            currentInputConnection?.commitText(text, 1)
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Log.e(TAG, "=== Transcription failed ===")
        Log.e(TAG, "Error message: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WhisperInputService created")
        
        recorderManager = RecorderManager()
        whisperTranscriber = WhisperTranscriber()
    }

    override fun onCreateInputView(): View {
        // Initializes recorder manager
        recorderManager = RecorderManager()
        
        // Generate a filename for recording audio file.
        recordedAudioFilename = File(this.cacheDir, "recorded.m4a").absolutePath

        // Gets whether the IME should offer IME switch option
        // this is available only since API 28
        val shouldOfferImeSwitch =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                val result = shouldOfferSwitchingToNextInputMethod()
                Log.d(TAG, "shouldOfferSwitchingToNextInputMethod (API 28+): $result")
                result
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                val result = inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
                Log.d(TAG, "shouldOfferSwitchingToNextInputMethod (API <28): $result")
                result
            }
        
        // Force show the switch button if there are multiple input methods available
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledInputMethods = inputMethodManager.enabledInputMethodList
        val forceShowSwitchButton = enabledInputMethods.size > 1
        Log.d(TAG, "Enabled input methods count: ${enabledInputMethods.size}")
        Log.d(TAG, "Force show switch button: $forceShowSwitchButton")
        
        val finalShouldOfferImeSwitch = shouldOfferImeSwitch || forceShowSwitchButton

        // Returns the keyboard after setting it up and inflating its layout
        return whisperKeyboard.setup(layoutInflater,
            finalShouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
        )
    }

    private fun startRecording() {
        Log.d(TAG, "=== Starting recording ===")
        try {
            val filename = File(this.cacheDir, "recorded.m4a").absolutePath
            Log.d(TAG, "Starting recorder with filename: $filename")
            
            val success = recorderManager.start(filename)
            if (success) {
                Log.d(TAG, "Recorder started successfully")
            } else {
                Log.e(TAG, "Failed to start recorder")
                whisperKeyboard.updateStatus(WhisperKeyboard.Status.IDLE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            whisperKeyboard.updateStatus(WhisperKeyboard.Status.IDLE)
        }
    }

    private fun stopRecording() {
        try {
            val success = recorderManager.stop()
            if (!success) {
                Log.e(TAG, "Recording failed - file may be corrupted")
                Toast.makeText(this, "Recording too short or failed", Toast.LENGTH_SHORT).show()
                whisperKeyboard.updateStatus(WhisperKeyboard.Status.IDLE)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            whisperKeyboard.updateStatus(WhisperKeyboard.Status.IDLE)
            return
        }
    }



    private fun onStartRecording() {
        startRecording()
    }

    private fun onCancelRecording() {
        Log.d(TAG, "=== Canceling recording ===")
        stopRecording()
    }

    private fun onStartTranscription(attachToEnd: String) {
        Log.d(TAG, "=== Starting transcription ===")
        Log.d(TAG, "Attach to end: \"$attachToEnd\"")
        
        stopRecording()
        
        // Check if audio file was created properly
        val audioFile = File(recordedAudioFilename)
        Log.d(TAG, "Audio file exists: ${audioFile.exists()}")
        if (audioFile.exists()) {
            Log.d(TAG, "Audio file size: ${audioFile.length()} bytes")
        }
        
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            AUDIO_MEDIA_TYPE,
            attachToEnd,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        
        // 移除停止录音的逻辑，不再在窗口显示时停止录音
        // 只在第一次显示窗口时自动开始录音

        // If this is the first time calling onWindowShown, it means this IME is just being switched to
        // Automatically starts recording after switching to Whisper Input (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            if (!isFirstTime) return@launch
            isFirstTime = false
            
            try {
                val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_RECORDING_START] ?: true
                }.first()
                
                if (isAutoStartRecording) {
                    Log.d(TAG, "Auto-starting recording")
                    whisperKeyboard.tryStartRecording()
                } else {
                    Log.d(TAG, "Auto-start recording disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking auto-start recording setting", e)
                // Default to true if there's an error
                Log.d(TAG, "Defaulting to auto-start recording")
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        
        // 不再自动停止录音，只重置键盘状态
        whisperKeyboard.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        
        // 在服务销毁时停止录音
        if (whisperKeyboard.isRecording()) {
            Log.d(TAG, "Service destroyed while recording - stopping recording")
            stopRecording()
        }
        
        whisperKeyboard.reset()
    }
}
