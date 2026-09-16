package com.xlollx.songport.ytmbridge

import kotlinx.serialization.Serializable

/** What crosses the process boundary to Songport. Field names are the contract: do not rename. */
@Serializable
data class PlaylistDto(val id: String, val name: String, val count: Int = -1)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    /** Id of the entry inside the playlist: needed to remove it. */
    val setVideoId: String? = null,
)

@Serializable
data class RemoveItem(val videoId: String, val setVideoId: String? = null)
