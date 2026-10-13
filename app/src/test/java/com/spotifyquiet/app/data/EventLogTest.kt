package com.spotifyquiet.app.data

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {

    private fun event(id: Long, title: String? = "Song $id") = PlaybackEvent(
        id = id,
        timestampMs = id * 1_000L,
        kind = EventKind.MEDIA_METADATA,
        classification = Classification.NORMAL_PLAYBACK,
        confidence = Confidence.HIGH,
        title = title,
        artist = "Artist",
        album = null,
        phase = "PLAYING",
        positionMs = null,
        durationMs = null,
        evidence = listOf("playing with complete metadata"),
    )

    @Test
    fun growthIsBounded() {
        val log = EventLog(maxSize = 3)
        for (id in 1L..10L) {
            log.add(event(id))
        }
        assertEquals(3, log.size)
        val titles = log.snapshot().map { it.title }
        assertEquals(listOf("Song 8", "Song 9", "Song 10"), titles)
    }

    @Test
    fun newestIsLast() {
        val log = EventLog(maxSize = 5)
        log.add(event(1))
        log.add(event(2))
        assertEquals("Song 2", log.last()?.title)
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = EventLog(maxSize = 5)
        log.add(event(1))
        log.clear()
        assertTrue(log.isEmpty)
        assertNull(log.last())
    }

    @Test
    fun snapshotIsACopy() {
        val log = EventLog(maxSize = 5)
        log.add(event(1))
        val copy = log.snapshot()
        log.clear()
        assertEquals(1, copy.size)
        assertTrue(log.isEmpty)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveMaxSizeIsRejected() {
        EventLog(maxSize = 0)
    }
}
