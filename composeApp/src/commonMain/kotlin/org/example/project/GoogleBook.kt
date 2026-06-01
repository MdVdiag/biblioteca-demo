package org.example.project

import kotlinx.serialization.Serializable

@Serializable
data class GoogleBook(val items: List<Volume>? = null)

@Serializable
data class Volume(val volumeInfo: VolumeInfo? = null)

@Serializable
data class VolumeInfo(
    val title: String? = null,
    val authors: List<String>? = null
)
@Serializable
data class ImageLinks(val thumbnail: String? = null)
