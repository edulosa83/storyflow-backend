package com.lalo.storyflow.network

import com.lalo.storyflow.model.MediaType
import com.lalo.storyflow.model.ResolvedStories
import com.lalo.storyflow.model.StoryMedia

class StoryRepository(
    private val api: StoryApi
) {
    suspend fun resolveStories(input: String): Result<ResolvedStories> {
        return runCatching {
            val response = api.resolveStories(ResolveRequest(input.trim()))

            val items = response.items.mapNotNull { dto ->
                val downloadUrl = dto.downloadUrl?.trim().orEmpty()
                val thumbnailUrl = dto.thumbnailUrl?.trim().orEmpty()
                val username = dto.username?.trim().orEmpty()

                if (downloadUrl.isBlank() || thumbnailUrl.isBlank() || username.isBlank()) {
                    return@mapNotNull null
                }

                StoryMedia(
                    id = dto.id?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "${username}_${downloadUrl.hashCode()}",
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
                    ?: input.trim(),
                source = response.source?.trim().takeUnless { it.isNullOrBlank() } ?: "custom-backend",
                items = items
            )
        }
    }
}
