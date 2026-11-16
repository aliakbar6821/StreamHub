package dev.lynx.streamhub.api

interface Extension {
    val id: String
    val name: String
    val version: Int

    suspend fun getHome(): List<HomeSection>
    suspend fun getGenres(): List<String> = emptyList()
    suspend fun search(query: String, page: Int = 1): List<MediaItem>
    suspend fun getDetails(id: String): MediaDetails
    suspend fun getStreams(episodeId: String): List<Stream>

    // Optional: return null if the extension has no schedule
    suspend fun getSchedule(): List<MediaItem>? = null
}
