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

package com.example.whispertoinput.keyboard

import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.math.MathUtils
import com.example.whispertoinput.R
import kotlin.math.log10
import kotlin.math.pow

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F
private const val AMPLITUDE_ANIMATION_DURATION: Long = 500
private val amplitudePowers: Array<Float> = arrayOf(0.5f, 1.0f, 2f, 3f)

class WhisperKeyboard {
    private enum class KeyboardStatus {
        Idle,             // Ready to start recording
        Recording,       // Currently recording
        Transcribing,    // Waiting for transcription results
    }

    enum class Status {
        IDLE,
        RECORDING,
        TRANSCRIBING
    }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (attachToEnd: String) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }
    private var onSpaceBar: () -> Unit = { }
    private var shouldShowRetry: () -> Boolean = { false }

    // Keyboard Status
    private var keyboardStatus: KeyboardStatus = KeyboardStatus.Idle
    
    // Check if currently recording
    fun isRecording(): Boolean {
        return keyboardStatus == KeyboardStatus.Recording
    }
    
    // Update status from external classes
    fun updateStatus(status: Status) {
        when (status) {
            Status.IDLE -> setKeyboardStatus(KeyboardStatus.Idle)
            Status.RECORDING -> setKeyboardStatus(KeyboardStatus.Recording)
            Status.TRANSCRIBING -> setKeyboardStatus(KeyboardStatus.Transcribing)
        }
    }

    // Views & Keyboard Layout
    private var keyboardView: ConstraintLayout? = null
    private var buttonMic: ImageButton? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var buttonRetry: ImageButton? = null
    private var labelStatus: TextView? = null
    private var buttonSpaceBar: ImageButton? = null
    private var waitingIcon: ProgressBar? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonPreviousIme: ImageButton? = null
    private var buttonSettings: ImageButton? = null
    private var micRippleContainer: ConstraintLayout? = null
    private var micRipples: Array<ImageView> = emptyArray()

    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (attachToEnd: String) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSpaceBar: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit,
        shouldShowRetry: () -> Boolean,
    ): View {
        // Inflate the keyboard layout & assign views
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as ConstraintLayout
        buttonMic = keyboardView!!.findViewById(R.id.btn_mic) as ImageButton
        buttonEnter = keyboardView!!.findViewById(R.id.btn_enter) as ImageButton
        buttonCancel = keyboardView!!.findViewById(R.id.btn_cancel) as ImageButton
        buttonRetry = keyboardView!!.findViewById(R.id.btn_retry) as ImageButton
        labelStatus = keyboardView!!.findViewById(R.id.label_status) as TextView
        buttonSpaceBar = keyboardView!!.findViewById(R.id.btn_space_bar) as ImageButton
        waitingIcon = keyboardView!!.findViewById(R.id.pb_waiting_icon) as ProgressBar
        buttonBackspace = keyboardView!!.findViewById(R.id.btn_backspace) as BackspaceButton
        buttonPreviousIme = keyboardView!!.findViewById(R.id.btn_previous_ime) as ImageButton
        buttonSettings = keyboardView!!.findViewById(R.id.btn_settings) as ImageButton
        micRippleContainer = keyboardView!!.findViewById(R.id.mic_ripples) as ConstraintLayout
        micRipples = arrayOf(
            keyboardView!!.findViewById(R.id.mic_ripple_0) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_1) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_2) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_3) as ImageView
        )

        // Hide buttonPreviousIme if necessary
        if (!shouldOfferImeSwitch) {
            buttonPreviousIme!!.visibility = View.GONE
        }

        // Set onClick listeners
        buttonMic!!.setOnClickListener { onButtonMicClick() }
        buttonEnter!!.setOnClickListener { onButtonEnterClick() }
        buttonCancel!!.setOnClickListener { onButtonCancelClick() }
        buttonRetry!!.setOnClickListener { onButtonRetryClick() }
        buttonSettings!!.setOnClickListener { onButtonSettingsClick() }
        buttonBackspace!!.setBackspaceCallback { onButtonBackspaceClick() }
        buttonSpaceBar!!.setOnClickListener { onButtonSpaceBarClick() }

        if (shouldOfferImeSwitch) {
            buttonPreviousIme!!.setOnClickListener { onButtonPreviousImeClick() }
        }

        // Set event listeners
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onSwitchIme = onSwitchIme
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter
        this.onSpaceBar = onSpaceBar
        this.shouldShowRetry = shouldShowRetry

        // Resets keyboard upon setup
        reset()

        // Returns the keyboard view (non-nullable)
        return keyboardView!!
    }

    fun reset() {
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording) {
            return
        }

        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )

        // decibel-like calculation
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)

        // normalizedPower ranges from 0 to 1.
        // The inner-most ripple should be the most sensitive to audio,
        // represented by a gamma-correction-like curve.
        for (micRippleIdx in micRipples.indices) {
            micRipples[micRippleIdx].clearAnimation()
            micRipples[micRippleIdx].alpha = normalizedPower.pow(amplitudePowers[micRippleIdx])
            micRipples[micRippleIdx].animate().alpha(0f).setDuration(AMPLITUDE_ANIMATION_DURATION)
                .start()
        }
    }

    fun tryStartRecording() {
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Recording)
            onStartRecording()
        }
    }

    fun tryCancelRecording() {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        }
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(attachToEnd)
        }
    }

    private fun onButtonSpaceBarClick() {
        // Upon button space bar click.
        // Recording -> Start transcribing (with a whitespace included)
        // else -> invokes onSpaceBar
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(" ")
        } else {
            onSpaceBar()
        }
    }

    private fun onButtonBackspaceClick() {
        // Currently, this onClick only makes a call to onButtonBackspace()
        this.onButtonBackspace()
    }

    private fun onButtonPreviousImeClick() {
        // Currently, this onClick only makes a call to onSwitchIme()
        this.onSwitchIme()
    }

    private fun onButtonSettingsClick() {
        // Currently, this onClick only makes a call to onOpenSettings()
        this.onOpenSettings()
    }

    private fun onButtonMicClick() {
        Log.d("WhisperKeyboard", "=== Mic button clicked (status: $keyboardStatus) ===")
        // Upon button mic click...
        // Idle -> Start Recording
        // Recording -> Finish Recording (without a newline)
        // Transcribing -> Nothing (to avoid double-clicking by mistake, which starts transcribing and then immediately cancels it)
        when (keyboardStatus) {
            KeyboardStatus.Idle -> {
                Log.d("WhisperKeyboard", "Idle -> Recording")
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }

            KeyboardStatus.Recording -> {
                Log.d("WhisperKeyboard", "Recording -> Transcribing (no newline)")
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing("")
            }

            KeyboardStatus.Transcribing -> {
                Log.d("WhisperKeyboard", "Already transcribing, ignoring click")
                return
            }
        }
    }

    private fun onButtonEnterClick() {
        // Upon button enter click.
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("\r\n")
        } else {
            onEnter()
        }
    }

    private fun onButtonCancelClick() {
        // Upon button cancel click.
        // Recording -> Cancel
        // Transcribing -> Cancel
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        } else if (keyboardStatus == KeyboardStatus.Transcribing) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelTranscribing()
        }
    }

    private fun onButtonRetryClick() {
        Log.d("WhisperKeyboard", "=== Retry button clicked (status: $keyboardStatus) ===")
        // Upon button retry click.
        // Idle -> Retry
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Idle) {
            Log.d("WhisperKeyboard", "Retrying transcription with existing audio file")
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("")
        } else {
            Log.d("WhisperKeyboard", "Retry button ignored (not idle)")
        }
    }

    private fun setKeyboardStatus(newStatus: KeyboardStatus) {
        Log.d("WhisperKeyboard", "Status change: $keyboardStatus -> $newStatus")
        val previousStatus = keyboardStatus
        keyboardStatus = newStatus
        
        when (newStatus) {
            KeyboardStatus.Idle -> {
                Log.d("WhisperKeyboard", "Setting up Idle state")
                labelStatus!!.setText(R.string.whisper_to_input)
                buttonMic!!.setImageResource(R.drawable.ic_microphone)
                buttonMic!!.setBackgroundResource(R.drawable.mic_button_background)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.INVISIBLE
                buttonRetry!!.visibility = if (shouldShowRetry()) View.VISIBLE else View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = false
            }

            KeyboardStatus.Recording -> {
                Log.d("WhisperKeyboard", "Setting up Recording state")
                labelStatus!!.setText(R.string.recording)
                buttonMic!!.setImageResource(R.drawable.ic_microphone_pressed)
                buttonMic!!.setBackgroundResource(R.drawable.mic_button_recording_background)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.VISIBLE
                keyboardView!!.keepScreenOn = true
            }

            KeyboardStatus.Transcribing -> {
                Log.d("WhisperKeyboard", "Setting up Transcribing state")
                labelStatus!!.setText(R.string.transcribing)
                buttonMic!!.setImageResource(R.drawable.ic_microphone_transcribing)
                buttonMic!!.setBackgroundResource(R.drawable.mic_button_transcribing_background)
                waitingIcon!!.visibility = View.VISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = true
            }
        }
    }
}
