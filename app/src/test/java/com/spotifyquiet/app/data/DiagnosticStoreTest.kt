package com.spotifyquiet.app.data

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticStoreTest {

    private fun event(id: Long, title: String? = "Song $id") = PlaybackEvent(
        id = id,
        timestampMs = id * 1_000L,
        kind = EventKind.MEDIA_METADATA,
        classification = Classification.POSSIBLE_AD,
        confidence = Confidence.MEDIUM,
        title = title,
        artist = "Artist",
        album = null,
        phase = "PLAYING",
        positionMs = null,
        durationMs = null,
        evidence = listOf("evidence"),
    )

    @Test
    fun markStoresManualLabel() {
        val store = DiagnosticStore()
        val mark = store.mark(event(1), ManualLabel.AD)
        assertEquals(ManualLabel.AD, mark.label)
        assertEquals(ManualLabel.AD, store.labelFor(1))
        assertEquals(1, store.size)
    }

    @Test
    fun relabellingReplacesPreviousLabel() {
        val store = DiagnosticStore()
        store.mark(event(1), ManualLabel.AD)
        store.mark(event(1), ManualLabel.NOT_AD)
        assertEquals(ManualLabel.NOT_AD, store.labelFor(1))
        assertEquals(1, store.size)
    }

    @Test
    fun unknownEventHasNoLabel() {
        val store = DiagnosticStore()
        assertNull(store.labelFor(42))
    }

    @Test
    fun clearRemovesAllLabels() {
        val store = DiagnosticStore()
        store.mark(event(1), ManualLabel.AD)
        store.mark(event(2), ManualLabel.NOT_AD)
        store.clear()
        assertEquals(0, store.size)
        assertNull(store.labelFor(1))
    }

    @Test
    fun storageIsBounded() {
        val store = DiagnosticStore(maxSize = 2)
        store.mark(event(1), ManualLabel.AD)
        store.mark(event(2), ManualLabel.AD)
        store.mark(event(3), ManualLabel.NOT_AD)
        assertEquals(2, store.size)
        assertNull("oldest label must be evicted", store.labelFor(1))
        assertEquals(ManualLabel.NOT_AD, store.labelFor(3))
    }
}
