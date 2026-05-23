package com.lalo.storyflow.intent

import android.net.Uri

class InstagramInputParser {

    fun normalize(rawInput: String): String {
        val cleaned = rawInput.trim()
        if (cleaned.isBlank()) return ""

        val firstUrl = extractFirstUrl(cleaned)
        if (firstUrl != null) {
            val username = extractUsernameFromInstagramUrl(firstUrl)
            return username ?: firstUrl
        }

        return cleaned.removePrefix("@").trim()
    }

    private fun extractFirstUrl(input: String): String? {
        val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        return urlRegex.find(input)?.value
    }

    private fun extractUsernameFromInstagramUrl(url: String): String? {
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase().orEmpty()
            val supportedHost = host.contains("instagram") || host == "instagr.am" || host == "www.instagr.am"
            if (!supportedHost) {
                return null
            }

            val segments = uri.pathSegments.filter { it.isNotBlank() }
            if (segments.isEmpty()) return null

            if (segments.firstOrNull() == "stories" && segments.size >= 2) {
                return segments[1]
            }

            val reserved = setOf(
                "p", "reel", "reels", "tv", "explore", "accounts",
                "stories", "direct", "share", "about"
            )
            val candidate = segments.first()
            if (candidate in reserved) null else candidate
        }.getOrNull()
    }
}
