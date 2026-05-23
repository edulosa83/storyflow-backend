package com.lalo.storyflow.model

enum class MediaType {
    IMAGE,
    VIDEO
}

data class StoryMedia(
    val id: String,
    val username: String,
    val mediaType: MediaType,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val takenAtIso: String?
)

data class ResolvedStories(
    val normalizedInput: String,
    val source: String,
    val items: List<StoryMedia>
)
