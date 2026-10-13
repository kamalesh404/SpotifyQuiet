package com.spotifyquiet.app.analysis

/**
 * Best-effort heuristic classification of Spotify playback.
 *
 * Honest limitations, mirrored in every branch below:
 * - Spotify does not publish an authoritative advertisement flag to other
 *   apps, so [Classification.POSSIBLE_AD] is a suspicion backed by recorded
 *   evidence, never a guarantee.
 * - Missing or changing metadata alone is NOT enough to call something an ad;
 *   when evidence is weak the result is [Classification.UNKNOWN].
 * - Pausing, resuming, buffering and normal track changes are explicitly not
 *   advertisements.
 */
class HeuristicPlaybackAnalyzer : PlaybackEventAnalyzer {

    override fun analyze(
        current: PlaybackSnapshot?,
        previous: PlaybackSnapshot?,
    ): AnalysisResult {
        if (current == null) {
            return AnalysisResult(
                classification = Classification.UNKNOWN,
                confidence = Confidence.LOW,
                evidence = listOf("no playback snapshot available from media session or notification"),
            )
        }

        val title = current.normalizedTitle()
        val artist = current.normalizedArtist()
        val evidence = mutableListOf<String>()

        // 1. Interruption-like phases are never advertisements.
        when (current.phase) {
            PlaybackPhase.BUFFERING -> return AnalysisResult(
                Classification.BUFFERING_OR_INTERRUPTION,
                Confidence.HIGH,
                listOf("playback phase is BUFFERING", current.describe()),
            )
            PlaybackPhase.ERROR -> return AnalysisResult(
                Classification.BUFFERING_OR_INTERRUPTION,
                Confidence.HIGH,
                listOf("playback phase is ERROR", current.describe()),
            )
            PlaybackPhase.STOPPED -> return AnalysisResult(
                Classification.BUFFERING_OR_INTERRUPTION,
                Confidence.HIGH,
                listOf("playback phase is STOPPED", current.describe()),
            )
            else -> Unit
        }

        // 2. Explicit advertisement wording in metadata is the strongest signal
        //    Spotify exposes (used by its own ad creatives), but it is still a
        //    text match, not an API flag.
        val markerField = when {
            title != null && isAdvertisementText(title) -> "title"
            artist != null && isAdvertisementText(artist) -> "artist"
            current.album != null && isAdvertisementText(current.album) -> "album"
            else -> null
        }
        if (markerField != null) {
            return AnalysisResult(
                Classification.POSSIBLE_AD,
                Confidence.HIGH,
                listOf(
                    "metadata $markerField contains the advertisement marker",
                    current.describe(),
                    "heuristic text match only; Spotify exposes no authoritative ad flag",
                ),
            )
        }

        // 3. Playing.
        if (current.phase == PlaybackPhase.PLAYING) {
            if (title == null) {
                val previousHadTitle = previous?.normalizedTitle() != null
                return if (previousHadTitle) {
                    AnalysisResult(
                        Classification.POSSIBLE_AD,
                        Confidence.MEDIUM,
                        listOf(
                            "metadata title disappeared while still playing, " +
                                "previous title was '${previous?.normalizedTitle()}'",
                            "duration=${current.durationMs ?: "<none>"}",
                            "consistent with an advertisement, but also with a metadata gap; " +
                                "verify manually before trusting this",
                        ),
                    )
                } else {
                    AnalysisResult(
                        Classification.UNKNOWN,
                        Confidence.LOW,
                        listOf(
                            "playing but no title or artist metadata was published",
                            current.describe(),
                        ),
                    )
                }
            }

            val previousTitle = previous?.normalizedTitle()
            if (previousTitle != null && previousTitle != title) {
                return AnalysisResult(
                    Classification.TRACK_TRANSITION,
                    Confidence.HIGH,
                    listOf("title changed from '$previousTitle' to '$title'", current.describe()),
                )
            }

            if (artist != null) {
                evidence += "playing with complete metadata (title and artist present)"
                evidence += current.describe()
                return AnalysisResult(Classification.NORMAL_PLAYBACK, Confidence.HIGH, evidence)
            }

            return AnalysisResult(
                Classification.UNKNOWN,
                Confidence.LOW,
                listOf(
                    "playing with a title but no artist; insufficient evidence for a classification",
                    current.describe(),
                ),
            )
        }

        // 4. Paused (or unknown) playback.
        if (current.phase == PlaybackPhase.PAUSED) {
            val previousTitle = previous?.normalizedTitle()
            if (previousTitle != null && title != null && previousTitle != title) {
                return AnalysisResult(
                    Classification.TRACK_TRANSITION,
                    Confidence.MEDIUM,
                    listOf(
                        "title changed while paused, from '$previousTitle' to '$title'",
                        current.describe(),
                    ),
                )
            }
            return AnalysisResult(
                Classification.NORMAL_PLAYBACK,
                Confidence.MEDIUM,
                listOf("playback paused with no metadata change; pausing is not an advertisement",
                    current.describe()),
            )
        }

        return AnalysisResult(
            Classification.UNKNOWN,
            Confidence.LOW,
            listOf(
                "playback phase is unknown and evidence is insufficient",
                current.describe(),
            ),
        )
    }

    private fun isAdvertisementText(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized == "advertisement" || normalized.contains("advertisement")
    }
}
