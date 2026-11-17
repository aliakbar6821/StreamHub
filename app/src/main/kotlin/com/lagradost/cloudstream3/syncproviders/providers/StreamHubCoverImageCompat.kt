package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Narrow compatibility model for plugins referencing the
 * CloudStream app-only AniListApi$CoverImage JVM class.
 * Does not implement AniList synchronization or authentication.
 */
data class `AniListApi$CoverImage`(
    @param:JsonProperty("medium")
    @get:JsonProperty("medium")
    val medium: String?,
    @param:JsonProperty("large")
    @get:JsonProperty("large")
    val large: String?,
    @param:JsonProperty("extraLarge")
    @get:JsonProperty("extraLarge")
    val extraLarge: String?,
)
