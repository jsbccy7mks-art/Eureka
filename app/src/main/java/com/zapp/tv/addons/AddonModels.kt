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
    /** Cast with roles and photos, when the add-on provides them (Cinemeta does). */
    val people: List<Person> = emptyList(),
    val writers: List<String> = emptyList(),
    val country: String? = null,
    val awards: String? = null,
    val released: String? = null,
)

data class Person(val name: String, val role: String?, val photo: String?)

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

    /** Quality as a number for sorting and limits: 2160, 1080, 720, 480 or 0 when unknown. */
    val qualityRank: Int
        get() = when (quality) {
            "4K" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            else -> 0
        }

    /**
     * Whether an add-on says this link is ready (already on the debrid service). Add-ons mark this in the
     * name: "[RD+]" or a lightning bolt for ready, "download" or an hourglass for not yet.
     */
    val markedReady: Boolean?
        get() {
            val t = "$name $description"
            val lower = t.lowercase()
            return when {
                "download]" in lower || "\u23F3" in t || "uncached" in lower || "not cached" in lower -> false
                "+]" in t || "\u26A1" in t || "instant" in lower || "cached" in lower -> true
                else -> null
            }
        }

    /**
     * The picture, from the release name: 0 normal (SDR), 1 HDR (HDR10, HDR10+, HLG), 2 Dolby Vision
     * with an HDR fallback (DV HDR10), 3 Dolby Vision only (often no fallback: odd colours without DV).
     */
    val pictureKind: Int
        get() {
            val t = text.lowercase()
            val dv = "dolby vision" in t || "dovi" in t || Regex("\\bdv\\b").containsMatchIn(t)
            val hdr = "hdr" in t || "hlg" in t
            return when {
                dv && hdr -> 2
                dv -> 3
                hdr -> 1
                else -> 0
            }
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

/** A subtitle file offered by a subtitles add-on (OpenSubtitles and similar). */
data class ExternalSubtitle(val url: String, val lang: String, val addonName: String)
