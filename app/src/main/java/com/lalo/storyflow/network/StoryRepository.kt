package com.lalo.storyflow.network

import com.lalo.storyflow.model.MediaType
import com.lalo.storyflow.model.ResolvedStories
import com.lalo.storyflow.model.StoryMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import retrofit2.HttpException

class StoryRepository(
    private val api: StoryApi
) {
    suspend fun resolveStories(input: String): Result<ResolvedStories> {
        val normalizedInput = input.trim()

        return runCatching {
            val response = resolveStoriesWithRetry(normalizedInput)

            val items = response.items.mapNotNull { dto ->
                val downloadUrl = dto.downloadUrl?.trim().orEmpty()
                val thumbnailUrl = dto.thumbnailUrl?.trim().orEmpty()
                val username = dto.username?.trim().orEmpty()

                if (downloadUrl.isBlank() || thumbnailUrl.isBlank() || username.isBlank()) {
                    return@mapNotNull null
                }

                StoryMedia(
                    id = buildStableMediaId(
                        rawId = dto.id,
                        username = username,
                        downloadUrl = downloadUrl
                    ),
                    username = username,
                    mediaType = when (dto.mediaType?.lowercase()) {
                        "video" -> MediaType.VIDEO
                        else -> MediaType.IMAGE
                    },
                    thumbnailUrl = thumbnailUrl,
                    downloadUrl = downloadUrl,
                    takenAtIso = dto.takenAtIso?.trim()
                )
            }

            ResolvedStories(
                normalizedInput = response.normalizedInput?.trim().takeUnless { it.isNullOrBlank() }
                    ?: normalizedInput,
                source = response.source?.trim().takeUnless { it.isNullOrBlank() } ?: "custom-backend",
                items = items
            )
        }.recoverCatching { throwable ->
            throw mapToUserFriendlyError(throwable)
        }
    }

    private suspend fun resolveStoriesWithRetry(input: String): ResolveResponse {
        return try {
            api.resolveStories(ResolveRequest(input))
        } catch (firstError: Throwable) {
            if (firstError is CancellationException) throw firstError
            if (!isTimeoutError(firstError)) throw firstError

            // Retry once for transient timeout spikes.
            delay(RETRY_DELAY_MS)
            api.resolveStories(ResolveRequest(input))
        }
    }

    private fun isTimeoutError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is SocketTimeoutException || current is InterruptedIOException) {
                return true
            }

            if (current.message?.contains("timeout", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun mapToUserFriendlyError(throwable: Throwable): Throwable {
        if (isTimeoutError(throwable)) {
            return IllegalStateException("Instagram tardó en responder. Intenta de nuevo.")
        }

        if (throwable is HttpException) {
            val detail = throwable.response()
                ?.errorBody()
                ?.string()
                ?.let { parseFastApiDetail(it) }

            return when (throwable.code()) {
                400 -> IllegalStateException(detail ?: "La entrada no es válida. Verifica URL o usuario.")
                404 -> IllegalStateException(detail ?: "No se encontró el perfil o la URL no es válida.")
                502 -> IllegalStateException(detail ?: "Instagram tardó en responder. Intenta de nuevo.")
                503 -> IllegalStateException(
                    detail ?: "Instagram bloqueó temporalmente la sesión. Espera unos minutos e intenta de nuevo."
                )
                else -> IllegalStateException(detail ?: "Error del servidor (${throwable.code()})")
            }
        }

        if (throwable.message?.contains("unable to resolve host", ignoreCase = true) == true) {
            return IllegalStateException("No hay conexión a internet o el backend no está disponible.")
        }

        return throwable
    }

    private fun parseFastApiDetail(body: String): String? {
        return runCatching {
            JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun buildStableMediaId(rawId: String?, username: String, downloadUrl: String): String {
        val urlToken = extractLongNumericToken(downloadUrl)
        if (urlToken != null) {
            return urlToken
        }

        val cleanedProviderId = rawId
            ?.trim()
            ?.replace(Regex("[^0-9A-Za-z_-]+"), "")
            .orEmpty()

        if (cleanedProviderId.isNotBlank() && !cleanedProviderId.matches(Regex("\\d{1,5}"))) {
            return cleanedProviderId
        }

        return "${username}_${shortStableHash(downloadUrl)}"
    }

    private fun extractLongNumericToken(value: String): String? {
        return Regex("(\\d{8,})")
            .findAll(value)
            .lastOrNull()
            ?.value
    }

    private fun shortStableHash(value: String): String {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
        return bytes.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
        }.take(16)
    }

    private companion object {
        const val RETRY_DELAY_MS = 600L
    }
}
