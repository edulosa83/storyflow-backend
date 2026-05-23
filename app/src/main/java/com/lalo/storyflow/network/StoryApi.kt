package com.lalo.storyflow.network

import retrofit2.http.Body
import retrofit2.http.POST

interface StoryApi {
    @POST("api/v1/stories/resolve")
    suspend fun resolveStories(@Body request: ResolveRequest): ResolveResponse
}

data class ResolveRequest(
    val input: String
)

data class ResolveResponse(
    val normalizedInput: String? = null,
    val source: String? = null,
    val items: List<StoryMediaDto> = emptyList()
)

data class StoryMediaDto(
    val id: String? = null,
    val username: String? = null,
    val mediaType: String? = null,
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val takenAtIso: String? = null
)
