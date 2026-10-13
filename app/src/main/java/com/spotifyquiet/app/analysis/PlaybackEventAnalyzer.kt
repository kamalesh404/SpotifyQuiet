package com.spotifyquiet.app.analysis

/**
 * Classifies one playback snapshot, optionally against the previous one.
 *
 * Kept behind an interface so detection can be unit tested and swapped without
 * touching monitoring, and so monitoring can be disabled independently of
 * detection.
 */
interface PlaybackEventAnalyzer {
    /**
     * @param current the newest merged snapshot, or null when nothing is known.
     * @param previous the last snapshot that was analysed, or null at start.
     */
    fun analyze(current: PlaybackSnapshot?, previous: PlaybackSnapshot?): AnalysisResult
}
