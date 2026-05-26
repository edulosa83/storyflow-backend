package com.lalo.storyflow.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.lalo.storyflow.model.MediaType
import com.lalo.storyflow.model.StoryMedia
import java.security.MessageDigest
import java.util.Locale

class StoryAlreadyDownloadedException : IllegalStateException("Storie o Stories ya descargadas")

class MediaDownloadManager(
    private val context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isDownloaded(media: StoryMedia): Boolean {
        val key = mediaKey(media)
        val stored = prefs.getStringSet(KEY_DOWNLOADED_STORIES, emptySet()).orEmpty()
        return stored.contains(key)
    }

    fun enqueue(media: StoryMedia): Result<Long> {
        if (isDownloaded(media)) {
            return Result.failure(StoryAlreadyDownloadedException())
        }

        return runCatching {
            val extension = when (media.mediaType) {
                MediaType.IMAGE -> "jpg"
                MediaType.VIDEO -> "mp4"
            }
            val mediaToken = mediaFileToken(media)

            val filename = buildString {
                append(media.username.ifBlank { "story" })
                append("_")
                append(mediaToken)
                append(".")
                append(extension)
            }.lowercase(Locale.US)

            val request = DownloadManager.Request(Uri.parse(media.downloadUrl))
                .setTitle("StoryFlow: $filename")
                .setDescription("Descargando ${media.mediaType.name.lowercase(Locale.US)}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType(
                    when (media.mediaType) {
                        MediaType.IMAGE -> "image/jpeg"
                        MediaType.VIDEO -> "video/mp4"
                    }
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "StoryFlow/$filename"
                )

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        }.onSuccess {
            markAsDownloaded(media)
        }
    }

    private fun markAsDownloaded(media: StoryMedia) {
        val set = prefs.getStringSet(KEY_DOWNLOADED_STORIES, emptySet()).orEmpty().toMutableSet()
        set.add(mediaKey(media))
        prefs.edit().putStringSet(KEY_DOWNLOADED_STORIES, set).apply()
    }

    private fun mediaKey(media: StoryMedia): String {
        return "${media.username}|${mediaFileToken(media)}|${media.mediaType.name}"
    }

    private fun mediaFileToken(media: StoryMedia): String {
        val cleanedId = media.id
            .trim()
            .replace(Regex("[^0-9A-Za-z_-]+"), "")

        if (cleanedId.isNotBlank() && !cleanedId.matches(Regex("\\d{1,5}"))) {
            return cleanedId.takeLast(48)
        }

        return shortStableHash(media.downloadUrl)
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
        const val PREFS_NAME = "storyflow_download_history"
        const val KEY_DOWNLOADED_STORIES = "downloaded_story_keys"
    }
}
