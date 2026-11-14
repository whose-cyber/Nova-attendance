package com.example.ui.viewmodel

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.GeminiChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface ImageAnalysisState {
    data object Idle : ImageAnalysisState
    data object Loading : ImageAnalysisState
    data class Streaming(val text: String) : ImageAnalysisState
    data class Success(val text: String) : ImageAnalysisState
    data class Error(val message: String) : ImageAnalysisState
}

data class AiAssistantUiState(
    val messages: List<GeminiChatMessage> = listOf(DEFAULT_WELCOME_MESSAGE),
    val isSending: Boolean = false,
    val chatError: String? = null,
    val lastFailedPrompt: String? = null,
    val lastFailedContext: String = "",
    val activeModelName: String = PRIMARY_MODEL_NAME,
    val preferFastLiteModel: Boolean = false,
    val fallbackBannerMessage: String? = null,
    val selectedImageUri: Uri? = null,
    val imageAnalysisState: ImageAnalysisState = ImageAnalysisState.Idle
) {
    companion object {
        const val PRIMARY_MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"

        val DEFAULT_WELCOME_MESSAGE = GeminiChatMessage(
            id = "welcome",
            isUser = false,
            text = "Hello! I am your NovaAttend Academic Coordinator AI. Ask me to analyze attendance shortages (<75%), draft student warning notices, summarize today's attendance, or troubleshoot Excel/CSV roster formatting."
        )
    }
}

class AttendanceAiViewModel : ViewModel() {

    private val chatSystemInstruction = content {
        text(
            "You are NovaAttend Academic Coordinator AI, a concise, helpful academic operations assistant " +
                "for a university/college attendance tracking app. You help Class Coordinators and Class Representatives " +
                "analyze student attendance trends, identify students below the 75% attendance threshold, draft professional " +
                "notices to students/parents, and format Excel (.xlsx) or CSV (.csv) rosters with Roll Number and Name columns. " +
                "Keep responses clear, structured, and actionable."
        )
    }

    private val imageSystemInstruction = content {
        text(
            "You are an expert Academic Document & Attendance Sheet Vision Analyzer. " +
                "When given a photo of a handwritten or printed attendance register, student roster, whiteboard, or timetable, " +
                "accurately extract visible roll numbers, student names, Present (P) / Absent (A) marks, counts, and notable observations. " +
                "Present your findings in clean, structured bullet points."
        )
    }

    private val primaryChatModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiAssistantUiState.PRIMARY_MODEL_NAME,
            systemInstruction = chatSystemInstruction
        )
    }

    private val fallbackChatModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiAssistantUiState.FALLBACK_MODEL_NAME,
            systemInstruction = chatSystemInstruction
        )
    }

    private val primaryImageModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiAssistantUiState.PRIMARY_MODEL_NAME,
            systemInstruction = imageSystemInstruction
        )
    }

    private val fallbackImageModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiAssistantUiState.FALLBACK_MODEL_NAME,
            systemInstruction = imageSystemInstruction
        )
    }

    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    fun setPreferFastLiteModel(useLite: Boolean) {
        _uiState.update {
            it.copy(
                preferFastLiteModel = useLite,
                activeModelName = if (useLite) {
                    AiAssistantUiState.FALLBACK_MODEL_NAME
                } else {
                    AiAssistantUiState.PRIMARY_MODEL_NAME
                },
                fallbackBannerMessage = null
            )
        }
    }

    fun retryLastMessage() {
        val state = _uiState.value
        val prompt = state.lastFailedPrompt ?: return
        if (state.isSending) return
        _uiState.update { current ->
            val lastMsg = current.messages.lastOrNull()
            val cleanedMessages = if (lastMsg != null && lastMsg.isUser && lastMsg.text == prompt) {
                current.messages.dropLast(1)
            } else {
                current.messages
            }
            current.copy(messages = cleanedMessages)
        }
        sendChatMessage(prompt, state.lastFailedContext)
    }

    fun sendChatMessage(userPrompt: String, liveClassroomContext: String = "") {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return

        val priorHistory = buildChatHistory(_uiState.value.messages)
        val userMsgId = UUID.randomUUID().toString()
        val botMsgId = UUID.randomUUID().toString()
        val preferLite = _uiState.value.preferFastLiteModel

        _uiState.update { state ->
            state.copy(
                messages = state.messages +
                    GeminiChatMessage(id = userMsgId, isUser = true, text = trimmed) +
                    GeminiChatMessage(id = botMsgId, isUser = false, text = "", isStreaming = true),
                isSending = true,
                chatError = null,
                lastFailedPrompt = null,
                fallbackBannerMessage = null
            )
        }

        viewModelScope.launch {
            val contextualMessage = if (liveClassroomContext.isNotBlank()) {
                "Current Classroom Live Snapshot:\n$liveClassroomContext\n\nUser Question: $trimmed"
            } else {
                trimmed
            }

            try {
                val primaryModel = if (preferLite) fallbackChatModel else primaryChatModel
                val primaryName = if (preferLite) {
                    AiAssistantUiState.FALLBACK_MODEL_NAME
                } else {
                    AiAssistantUiState.PRIMARY_MODEL_NAME
                }

                val firstAttempt = executeChatStream(
                    model = primaryModel,
                    history = priorHistory,
                    messageText = contextualMessage,
                    botMsgId = botMsgId
                )

                if (firstAttempt.isSuccess) {
                    _uiState.update { it.copy(activeModelName = primaryName) }
                    return@launch
                }

                val firstError = firstAttempt.exceptionOrNull()
                if (firstError is CancellationException && firstError !is TimeoutCancellationException) {
                    throw firstError
                }

                Log.w(TAG, "Primary chat model ($primaryName) failed, checking fallback", firstError)

                // Automatic fallback between primary and fast-lite model on high demand or transient failures
                val secondaryModel = if (preferLite) primaryChatModel else fallbackChatModel
                val secondaryName = if (preferLite) {
                    AiAssistantUiState.PRIMARY_MODEL_NAME
                } else {
                    AiAssistantUiState.FALLBACK_MODEL_NAME
                }

                updateBotMessage(botMsgId, "", isStreaming = true)
                _uiState.update {
                    it.copy(
                        activeModelName = secondaryName,
                        fallbackBannerMessage = "Primary model busy — switched seamlessly to $secondaryName."
                    )
                }

                val secondAttempt = executeChatStream(
                    model = secondaryModel,
                    history = priorHistory,
                    messageText = contextualMessage,
                    botMsgId = botMsgId
                )

                if (secondAttempt.isSuccess) {
                    return@launch
                }

                val finalError = secondAttempt.exceptionOrNull() ?: firstError
                if (finalError is CancellationException && finalError !is TimeoutCancellationException) {
                    throw finalError
                }
                Log.e(TAG, "All Gemini chat attempts failed", finalError)
                removeBotMessage(botMsgId)
                _uiState.update {
                    it.copy(
                        chatError = formatUserFriendlyAiError(finalError),
                        lastFailedPrompt = trimmed,
                        lastFailedContext = liveClassroomContext,
                        fallbackBannerMessage = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected chat failure", e)
                removeBotMessage(botMsgId)
                _uiState.update {
                    it.copy(
                        chatError = formatUserFriendlyAiError(e),
                        lastFailedPrompt = trimmed,
                        lastFailedContext = liveClassroomContext
                    )
                }
            } finally {
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        messages = state.messages.map { msg ->
                            if (msg.id == botMsgId && msg.isStreaming) {
                                msg.copy(isStreaming = false)
                            } else {
                                msg
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun executeChatStream(
        model: GenerativeModel,
        history: List<Content>,
        messageText: String,
        botMsgId: String
    ): Result<String> {
        val accumulated = StringBuilder()
        return try {
            withTimeout(MODEL_TIMEOUT_MS) {
                val chatSession = model.startChat(history = history)
                chatSession.sendMessageStream(messageText).collect { chunk ->
                    val part = chunk.text.orEmpty()
                    if (part.isNotEmpty()) {
                        accumulated.append(part)
                        updateBotMessage(botMsgId, accumulated.toString(), isStreaming = true)
                    }
                }
            }
            val finalText = accumulated.toString().trim()
            if (finalText.isNotEmpty()) {
                updateBotMessage(botMsgId, finalText, isStreaming = false)
                Result.success(finalText)
            } else {
                // Unary fallback if stream yielded zero text chunks
                val unary = withTimeout(MODEL_TIMEOUT_MS) {
                    model.startChat(history = history).sendMessage(messageText)
                }
                val unaryText = unary.text?.trim().orEmpty()
                if (unaryText.isNotEmpty()) {
                    updateBotMessage(botMsgId, unaryText, isStreaming = false)
                    Result.success(unaryText)
                } else {
                    Result.failure(IllegalStateException("Gemini returned an empty response."))
                }
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(e)
            } else {
                throw e
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildChatHistory(messages: List<GeminiChatMessage>): List<Content> {
        // Keep only completed alternating user/model turns (skip welcome message and empty/streaming turns)
        val validMessages = messages.filter {
            !it.id.startsWith("welcome") && !it.isStreaming && it.text.isNotBlank()
        }
        val history = mutableListOf<Content>()
        var expectUser = true
        for (msg in validMessages) {
            if (msg.isUser && expectUser) {
                history.add(content("user") { text(msg.text) })
                expectUser = false
            } else if (!msg.isUser && !expectUser) {
                history.add(content("model") { text(msg.text) })
                expectUser = true
            }
        }
        // Gemini history should end on a model turn before sending the next user turn
        if (history.size % 2 != 0) {
            history.removeAt(history.lastIndex)
        }
        // Cap context window to recent 12 turns (24 messages) for low latency
        return if (history.size > 24) history.takeLast(24) else history
    }

    fun resetChat() {
        _uiState.update {
            it.copy(
                messages = listOf(
                    GeminiChatMessage(
                        id = "welcome_${System.currentTimeMillis()}",
                        isUser = false,
                        text = "Started a fresh conversation. Ask me anything about your classroom attendance or roster!"
                    )
                ),
                isSending = false,
                chatError = null,
                lastFailedPrompt = null,
                lastFailedContext = "",
                fallbackBannerMessage = null
            )
        }
    }

    fun clearChatError() {
        _uiState.update { it.copy(chatError = null, lastFailedPrompt = null) }
    }

    fun selectImageForAnalysis(uri: Uri?) {
        _uiState.update {
            it.copy(
                selectedImageUri = uri,
                imageAnalysisState = ImageAnalysisState.Idle
            )
        }
    }

    fun analyzeSelectedImage(
        contentResolver: ContentResolver,
        promptText: String
    ) {
        val currentAnalysis = _uiState.value.imageAnalysisState
        if (currentAnalysis is ImageAnalysisState.Loading || currentAnalysis is ImageAnalysisState.Streaming) {
            return
        }
        val uri = _uiState.value.selectedImageUri ?: return
        val effectivePrompt = promptText.trim().ifBlank {
            "Analyze this attendance sheet or roster photo. List any visible student roll numbers, names, Present/Absent marks, and a quick summary count."
        }

        viewModelScope.launch {
            _uiState.update { it.copy(imageAnalysisState = ImageAnalysisState.Loading) }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeScaledBitmap(contentResolver, uri, maxDimension = 1280)
                } ?: throw IllegalStateException("Could not decode the selected image.")

                val preferLite = _uiState.value.preferFastLiteModel
                val primaryModel = if (preferLite) fallbackImageModel else primaryImageModel
                val secondaryModel = if (preferLite) primaryImageModel else fallbackImageModel

                val firstAttempt = executeImageStream(primaryModel, bitmap, effectivePrompt)
                if (firstAttempt.isSuccess) {
                    _uiState.update {
                        it.copy(imageAnalysisState = ImageAnalysisState.Success(firstAttempt.getOrThrow()))
                    }
                    return@launch
                }

                val firstErr = firstAttempt.exceptionOrNull()
                if (firstErr is CancellationException && firstErr !is TimeoutCancellationException) {
                    throw firstErr
                }
                Log.w(TAG, "Primary vision model failed, attempting fallback model", firstErr)

                val secondAttempt = executeImageStream(secondaryModel, bitmap, effectivePrompt)
                if (secondAttempt.isSuccess) {
                    _uiState.update {
                        it.copy(imageAnalysisState = ImageAnalysisState.Success(secondAttempt.getOrThrow()))
                    }
                    return@launch
                }

                val finalErr = secondAttempt.exceptionOrNull() ?: firstErr
                if (finalErr is CancellationException && finalErr !is TimeoutCancellationException) {
                    throw finalErr
                }
                Log.e(TAG, "Image analysis failed on all models", finalErr)
                _uiState.update {
                    it.copy(imageAnalysisState = ImageAnalysisState.Error(formatUserFriendlyAiError(finalErr)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image analysis error", e)
                _uiState.update {
                    it.copy(imageAnalysisState = ImageAnalysisState.Error(formatUserFriendlyAiError(e)))
                }
            }
        }
    }

    private suspend fun executeImageStream(
        model: GenerativeModel,
        bitmap: Bitmap,
        prompt: String
    ): Result<String> {
        val accumulated = StringBuilder()
        return try {
            withTimeout(MODEL_TIMEOUT_MS) {
                model.generateContentStream(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                ).collect { chunk ->
                    val part = chunk.text.orEmpty()
                    if (part.isNotEmpty()) {
                        accumulated.append(part)
                        _uiState.update {
                            it.copy(imageAnalysisState = ImageAnalysisState.Streaming(accumulated.toString()))
                        }
                    }
                }
            }
            val resultText = accumulated.toString().trim()
            if (resultText.isNotEmpty()) {
                Result.success(resultText)
            } else {
                Result.failure(IllegalStateException("Gemini returned an empty image analysis."))
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(e)
            } else {
                throw e
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun updateBotMessage(id: String, text: String, isStreaming: Boolean) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == id) msg.copy(text = text, isStreaming = isStreaming) else msg
                }
            )
        }
    }

    private fun removeBotMessage(id: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == id })
        }
    }

    private fun formatUserFriendlyAiError(error: Throwable?): String {
        if (error is TimeoutCancellationException) {
            return "Gemini took too long to respond due to high demand or slow network. Tap Retry or switch to Fast Lite."
        }
        val raw = error?.localizedMessage.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("429") || lower.contains("quota") || lower.contains("resource_exhausted") ||
                lower.contains("high demand") || lower.contains("overloaded") || lower.contains("503") ||
                lower.contains("unavailable") ->
                "Gemini is currently experiencing high demand. Please wait a moment and tap Retry."
            lower.contains("403") || lower.contains("app check") || lower.contains("appcheck") ->
                "App Check attestation failed (HTTP 403). Verify network connectivity and relaunch from the signed build."
            lower.contains("network") || lower.contains("unable to resolve host") || lower.contains("connect") ->
                "Network connection unavailable. Check your internet connection and tap Retry."
            raw.isNotBlank() -> raw
            else -> "Unable to reach Gemini right now. Please tap Retry."
        }
    }

    private fun decodeScaledBitmap(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var sampleSize = 1
            while (origWidth / sampleSize > maxDimension || origHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image bitmap", e)
            null
        }
    }

    private companion object {
        const val TAG = "AttendanceAiVM"
        const val MODEL_TIMEOUT_MS = 45_000L
    }
}
