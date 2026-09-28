package com.example.phonediary.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object TextScanHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeTextFromImage(context: Context, imageUri: Uri): String? {
        val bitmap = loadBitmap(context, imageUri) ?: return null
        return recognizeTextFromBitmap(bitmap)
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    continuation.resume(text.ifBlank { null })
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }
}
