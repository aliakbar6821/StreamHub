package dev.lynx.streamhub.api

enum class MediaType { MOVIE, SERIES, ANIME }

enum class MediaStatus { AIRING, COMPLETED, UPCOMING, UNKNOWN }

data class MediaItem(
    val id: String,
    val title: String,
    val poster: String? = null,
    val type: MediaType = MediaType.SERIES,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val year: Int? = null,
    val status: MediaStatus = MediaStatus.UNKNOWN,
    val description: String? = null,
)

data class Episode(
    val id: String,
    val number: Float,
    val title: String? = null,
    val thumbnail: String? = null,
)

data class HomeSection(
    val title: String,
    val items: List<MediaItem>,
)

data class MediaDetails(
    val item: MediaItem,
    val episodes: List<Episode>,
)

data class Stream(
    val url: String,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
