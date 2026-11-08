package com.kmk.turkishtv.mux

/**
 * Release-name attribute parser (AIOStreams-style): extracts resolution,
 * quality, encode and HDR/DV flags from torrent release names like
 * "Inception.2010.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265".
 */
object ReleaseParser {

    fun parse(name: String): StreamAttrs {
        val n = name.lowercase()

        val resolution = when {
            Regex("(2160|4k|uhd)").containsMatchIn(n) -> 2160
            Regex("1080p|1080i|1080").containsMatchIn(n) -> 1080
            Regex("720p|720").containsMatchIn(n) -> 720
            Regex("480p|dvdrip|hdrip(?!.*720)").containsMatchIn(n) -> 480
            else -> 0
        }

        val quality = when {
            n.contains("remux") -> "Remux"
            n.contains("blu") -> "BluRay"          // bluray / brrip / bdrip / blu-ray
            n.contains("web-dl") || n.contains("webdl") || n.contains("web ") || n.endsWith("web") -> "WEB-DL"
            n.contains("webrip") -> "WEBRip"
            n.contains("dvdrip") -> "DVDRip"
            n.contains("hdrip") -> "HDRip"
            else -> "WEB"
        }

        val encode = when {
            n.contains("x265") || n.contains("h265") || n.contains("hevc") -> "x265"
            n.contains("x264") || n.contains("h264") || n.contains("avc") -> "x264"
            n.contains("av1") -> "AV1"
            else -> ""
        }

        val hdr = Regex("\\bhdr(10)?\\b|hdr10\\+?").containsMatchIn(n)
        val dv = Regex("dolby.?vision|\\bdv\\b").containsMatchIn(n)

        return StreamAttrs(resolution, quality, encode, hdr, dv)
    }
}
