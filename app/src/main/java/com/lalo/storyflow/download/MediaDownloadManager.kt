package com.lalo.storyflow.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.lalo.storyflow.model.MediaType
import com.lalo.storyflow.model.StoryMedia
import java.util.Locale

class MediaDownloadManager(
    private val context: Context
) {

    fun enqueue(media: StoryMedia): Result<Long> {
        return runCatching {
            val extension = when (media.mediaType) {
                MediaType.IMAGE -> "jpg"
                MediaType.VIDEO -> "mp4"
            }

            val filename = buildString {
                append(media.username.ifBlank { "story" })
                append("_")
                append(media.id.filter { it.isLetterOrDigit() }.take(20).ifBlank { System.currentTimeMillis() })
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
        }
    }
}
