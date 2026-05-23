package com.lalo.storyflow.config

import android.content.Context
import com.lalo.storyflow.BuildConfig
import java.net.URI

class BackendConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String {
        val stored = prefs.getString(KEY_BACKEND_URL, null).orEmpty()
        return if (stored.isBlank()) {
            normalize(BuildConfig.BACKEND_BASE_URL)
        } else {
            normalize(stored)
        }
    }

    fun saveBaseUrl(rawUrl: String): String {
        val normalized = normalize(rawUrl)
        prefs.edit().putString(KEY_BACKEND_URL, normalized).apply()
        return normalized
    }

    fun resetToDefault(): String {
        prefs.edit().remove(KEY_BACKEND_URL).apply()
        return normalize(BuildConfig.BACKEND_BASE_URL)
    }

    fun normalize(rawUrl: String): String {
        var candidate = rawUrl.trim()
        if (candidate.isBlank()) {
            throw IllegalArgumentException("Ingresa la URL del backend")
        }

        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "https://$candidate"
        }

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("URL inválida")
        }

        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "http") {
            throw IllegalArgumentException("La URL debe iniciar con http:// o https://")
        }

        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("La URL no tiene host válido")
        }

        if (uri.query != null || uri.fragment != null) {
            throw IllegalArgumentException("La URL no debe incluir ?query ni #fragment")
        }

        return if (candidate.endsWith('/')) candidate else "$candidate/"
    }

    private companion object {
        const val PREFS_NAME = "storyflow_prefs"
        const val KEY_BACKEND_URL = "backend_base_url"
    }
}
