package com.sahidcode404.camx.core.camera.acquisition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceCorpusTest {
    @Test
    fun sourceManifestIdIsDeterministic() {
        val identity = acquisitionIdentity()
        val digest = CanonicalRasterHasher.hash(
            identity.representation,
            listOf(SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))),
        )
        assertEquals(
            SourceManifestRecord.create(identity, digest).sourceId,
            SourceManifestRecord.create(identity, digest).sourceId,
        )
    }

    @Test
    fun corpusEnforcesEntryAndByteBounds() {
        val firstIdentity = acquisitionIdentity(captureToken = 1L)
        val secondIdentity = acquisitionIdentity(captureToken = 2L)
        val first = record(firstIdentity)
        val second = record(secondIdentity)
        val byEntries = BoundedSourceCorpusBuilder(maxEntries = 1, maxCanonicalBytes = 100)
        byEntries.add(first)
        assertThrows(IllegalArgumentException::class.java) { byEntries.add(second) }

        val byBytes = BoundedSourceCorpusBuilder(maxEntries = 2, maxCanonicalBytes = first.canonicalRaster.byteCount)
        byBytes.add(first)
        assertThrows(IllegalArgumentException::class.java) { byBytes.add(second) }
    }

    @Test
    fun frozenCorpusIsSortedImmutableAndStratified() {
        val later = record(acquisitionIdentity(captureToken = 9L))
        val earlier = record(acquisitionIdentity(captureToken = 3L))
        val builder = BoundedSourceCorpusBuilder(maxEntries = 4, maxCanonicalBytes = 1_024)
        builder.add(later)
        builder.add(earlier)
        val snapshot = builder.freeze()
        assertEquals(snapshot.records.sortedBy { it.sourceId.value }, snapshot.records)
        assertEquals(2, snapshot.records.size)
        assertEquals(1, snapshot.stratifiedCounts().size)
        assertEquals(2, snapshot.stratifiedCounts().values.single())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.records as MutableList<SourceManifestRecord>).clear()
        }
    }

    @Test
    fun duplicateSourceRecordIsRejected() {
        val record = record(acquisitionIdentity())
        val builder = BoundedSourceCorpusBuilder(maxEntries = 2, maxCanonicalBytes = 100)
        builder.add(record)
        assertThrows(IllegalArgumentException::class.java) { builder.add(record) }
    }

    private fun record(identity: AcquisitionIdentity): SourceManifestRecord {
        val digest = CanonicalRasterHasher.hash(
            identity.representation,
            listOf(SourcePlane(0, byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))),
        )
        return SourceManifestRecord.create(identity, digest)
    }
}
