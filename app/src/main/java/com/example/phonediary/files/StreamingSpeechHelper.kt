package com.example.phonediary.files

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Two speech modes sharing one engine:
 *  - startOneShot(): ONLINE. Standard cloud recognizer, one final result. Needs internet.
 *  - start():        OFFLINE-first. On-device recognizer when available (Android 12+),
 *                    live partial results while speaking.
 */
class StreamingSpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** True only when the phone has a working, validated internet connection. */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** OFFLINE-first mode with live partial results — used by the 🎙️ button. */
    fun start(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        beginSession(
            online = false,
            partialResultsEnabled = true,
            onPartialResult = onPartialResult,
            onResult = onFinalResult,
            onError = onError,
            onListeningStateChanged = onListeningStateChanged
        )
    }

    /** ONLINE one-shot mode, a single final result — used by the 🎤 button. */
    fun startOneShot(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        beginSession(
            online = true,
            partialResultsEnabled = false,
            onPartialResult = {},
            onResult = onResult,
            onError = onError,
            onListeningStateChanged = onListeningStateChanged
        )
    }

    private fun beginSession(
        online: Boolean,
        partialResultsEnabled: Boolean,
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        if (!isAvailable()) {
            onError("Speech recognition not available on this device")
            return
        }
        if (online && !isOnline()) {
            onError("No internet connection — the 🎤 mic needs internet. Use 🎙️ for offline.")
            return
        }
        stop()

        // Online: always the standard (cloud) recognizer. Offline: on-device if it exists.
        val useOnDevice = !online && isOnDeviceAvailable()
        runSession(useOnDevice, online, partialResultsEnabled, onPartialResult, onResult, onError, onListeningStateChanged)
    }

    private fun runSession(
        useOnDevice: Boolean,
        online: Boolean,
        partialResultsEnabled: Boolean,
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        val newRecognizer = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = newRecognizer

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningStateChanged(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                onListeningStateChanged(false)
            }
            override fun onError(error: Int) {
                isListening = false
                onListeningStateChanged(false)

                // On-device model failed (not just "heard nothing"): retry once with the standard recognizer.
                if (useOnDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    newRecognizer.destroy()
                    recognizer = null
                    runSession(false, online, partialResultsEnabled, onPartialResult, onResult, onError, onListeningStateChanged)
                    return
                }
                onError(errorMessage(error, online))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningStateChanged(false)
                val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!finalText.isNullOrBlank()) onResult(finalText)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!partialResultsEnabled) return
                val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partialText.isNullOrBlank()) onPartialResult(partialText)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResultsEnabled)
            // Online mode explicitly does not ask for the offline model.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online && useOnDevice)
        }
        newRecognizer.startListening(recognizerIntent)
    }

    private fun errorMessage(error: Int, online: Boolean): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet too slow — try again, or use 🎙️ for offline"
        SpeechRecognizer.ERROR_NETWORK ->
            if (online) "Network error — check your internet, or use 🎙️ for offline"
            else "No offline speech model installed — download one in Settings → Languages & input → Voice input"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error — try again in a moment"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        else -> "Speech recognition error ($error)"
    }

    /**
     * Cancels immediately and reports not-listening. Plain stopListening()
     * doesn't always trigger a callback promptly on every device.
     */
    fun forceStop(onListeningStateChanged: (Boolean) -> Unit) {
        recognizer?.apply {
            try { cancel() } catch (e: Exception) { /* ignore */ }
            try { destroy() } catch (e: Exception) { /* ignore */ }
        }
        recognizer = null
        isListening = false
        onListeningStateChanged(false)
    }

    fun stop() {
        recognizer?.apply {
            try { stopListening() } catch (e: Exception) { /* ignore */ }
            destroy()
        }
        recognizer = null
        isListening = false
    }
}
