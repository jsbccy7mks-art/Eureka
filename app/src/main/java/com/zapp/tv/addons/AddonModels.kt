package com.zapp.tv.addons

/** One extra parameter a catalogue accepts, e.g. "search", "genre" or "skip". */
data class ExtraDef(val name: String, val isRequired: Boolean, val options: List<String>)

data class CatalogDef(val type: String, val id: String, val name: String, val extras: List<ExtraDef>) {
    val searchable: Boolean get() = extras.any { it.name == "search" }

    /** Catalogues that can be shown as a plain row (no required filter or search term). */
    val browsable: Boolean get() = extras.none { it.isRequired }

    val pageable: Boolean get() = extras.any { it.name == "skip" }
}

data class ResourceDef(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Manifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<ResourceDef>,
    val catalogs: List<CatalogDef>,
    val configurationRequired: Boolean,
) {
    /** Whether this add-on answers [resource] requests for this type and id. */
    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = r.idPrefixes ?: idPrefixes
        if (prefixes.isNullOrEmpty()) return true
        return prefixes.any { id.startsWith(it) }
    }
}

/** A title in a catalogue row. */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: String,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val genres: List<String>,
)

/** An episode (or for some add-ons a part of a film). */
data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
)

data class MetaDetail(
    val preview: MetaPreview,
    val runtime: String?,
    val cast: List<String>,
    val directors: List<String>,
    val videos: List<Video>,
    val trailerYoutube: String?,
)

/** One playable (or not directly playable) source from an add-on. */
data class StreamItem(
    val addonName: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val ytId: String?,
    val name: String,
    val description: String,
    val headers: Map<String, String>,
    val filename: String?,
    val bingeGroup: String?,
) {
    val playable: Boolean get() = !url.isNullOrBlank()

    private val text: String get() = "$name\n$description\n${filename.orEmpty()}"

    val quality: String?
        get() {
            val t = text.lowercase()
            return when {
                "2160" in t || Regex("\\b4k\\b").containsMatchIn(t) || "uhd" in t -> "4K"
                "1080" in t -> "1080p"
                "720" in t -> "720p"
                "480" in t -> "480p"
                else -> null
            }
        }

    val size: String?
        get() = Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB|GiB|MiB)", RegexOption.IGNORE_CASE).find(text)?.value

    val sizeBytes: Long
        get() {
            val m = Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB|GiB|MiB)", RegexOption.IGNORE_CASE).find(text) ?: return 0
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
            val unit = m.groupValues[2].lowercase()
            return (n * if (unit.startsWith("g")) 1_073_741_824.0 else 1_048_576.0).toLong()
        }

    /** Tags such as HDR, Dolby Vision, HEVC or Atmos found in the description. */
    val tags: List<String>
        get() {
            val t = text.lowercase()
            return buildList {
                if ("dolby vision" in t || Regex("\\bdv\\b").containsMatchIn(t)) add("Dolby Vision")
                if ("hdr" in t) add("HDR")
                if ("hevc" in t || "x265" in t || "h265" in t || "h.265" in t) add("HEVC")
                if ("atmos" in t) add("Atmos")
                if ("remux" in t) add("Remux")
            }
        }
}
