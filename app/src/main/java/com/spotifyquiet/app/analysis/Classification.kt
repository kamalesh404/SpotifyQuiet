package com.spotifyquiet.app.analysis

/**
 * Coarse outcome of analysing one playback snapshot.
 *
 * Spotify does not expose a definitive "this is an advertisement" flag in the
 * metadata an external app is allowed to read, so [POSSIBLE_AD] is a heuristic
 * suspicion, never a guarantee.
 */
enum class Classification {
    NORMAL_PLAYBACK,
    TRACK_TRANSITION,
    POSSIBLE_AD,
    BUFFERING_OR_INTERRUPTION,
    UNKNOWN,
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class AnalysisResult(
    val classification: Classification,
    val confidence: Confidence,
    val evidence: List<String>,
) {
    init {
        require(evidence.isNotEmpty()) { "every analysis must record its evidence" }
    }
}
