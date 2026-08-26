package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreviewFrameMetricsTest {
    private val requested = PreviewFpsRequest(true, 30, 60)
    private val resolved = CameraFpsCapability(30, 60)

    @Test
    fun noTimestampsProduceEmptySnapshot() {
        assertEmpty(metrics())
    }

    @Test
    fun oneTimestampProducesZeroIntervals() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(1_000_000_000L)
        assertEmpty(metrics)
    }

    @Test
    fun twoValidTimestampsProduceOneInterval() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(1_000_000_000L)
        metrics.recordSensorTimestamp(1_033_333_333L)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.sampleCount)
        assertEquals(33_333_333L, snapshot.p50FrameIntervalNs)
        assertEquals(33_333_333L, snapshot.p95FrameIntervalNs)
    }

    @Test
    fun regularThirtyFpsSequenceProducesFiniteThirtyishAverage() {
        val metrics = metrics(capacity = 8)
        recordIntervals(metrics, List(6) { 33_333_333L })
        val fps = requireNotNull(metrics.snapshot().movingAverageFps)
        assertEquals(30.0000003, fps, 0.00001)
    }

    @Test
    fun regularSixtyFpsSequenceProducesFiniteSixtyishAverage() {
        val metrics = metrics(capacity = 8)
        recordIntervals(metrics, List(6) { 16_666_667L })
        val fps = requireNotNull(metrics.snapshot().movingAverageFps)
        assertEquals(59.9999988, fps, 0.00001)
    }

    @Test
    fun duplicateTimestampIsIgnoredWithoutChangingBaseline() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(200L)
        metrics.recordSensorTimestamp(200L)
        metrics.recordSensorTimestamp(300L)
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.sampleCount)
        assertEquals(100L, snapshot.p50FrameIntervalNs)
        assertEquals(100L, snapshot.p95FrameIntervalNs)
    }

    @Test
    fun backwardsTimestampIsIgnoredWithoutChangingBaseline() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(200L)
        metrics.recordSensorTimestamp(150L)
        metrics.recordSensorTimestamp(300L)
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.sampleCount)
        assertEquals(100L, snapshot.p50FrameIntervalNs)
    }

    @Test
    fun zeroTimestampIsIgnored() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(0L)
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(200L)
        assertEquals(1, metrics.snapshot().sampleCount)
    }

    @Test
    fun negativeTimestampIsIgnored() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(Long.MIN_VALUE)
        metrics.recordSensorTimestamp(-1L)
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(200L)
        assertEquals(1, metrics.snapshot().sampleCount)
    }

    @Test
    fun invalidTimestampsBetweenValidSamplesDoNotPoisonNextInterval() {
        val metrics = metrics()
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(0L)
        metrics.recordSensorTimestamp(-5L)
        metrics.recordSensorTimestamp(50L)
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(250L)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.sampleCount)
        assertEquals(150L, snapshot.p50FrameIntervalNs)
    }

    @Test
    fun minimumCapacityTwoIsAcceptedAndRemainsBounded() {
        val metrics = metrics(capacity = 2)
        recordIntervals(metrics, listOf(10L, 20L, 30L))
        assertEquals(2, metrics.snapshot().sampleCount)
    }

    @Test
    fun maximumCapacityFourThousandNinetySixIsAccepted() {
        val metrics = metrics(capacity = 4_096)
        recordIntervals(metrics, listOf(10L))
        assertEquals(1, metrics.snapshot().sampleCount)
    }

    @Test
    fun capacitiesOutsideBoundsAreRejected() {
        for (capacity in listOf(Int.MIN_VALUE, -1, 0, 1, 4_097, Int.MAX_VALUE)) {
            try {
                metrics(capacity = capacity)
                fail("Expected capacity $capacity to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected construction-time bound rejection.
            }
        }
    }

    @Test
    fun ringFillsWithoutExceedingCapacity() {
        val metrics = metrics(capacity = 3)
        recordIntervals(metrics, listOf(10L, 20L, 30L))
        assertEquals(3, metrics.snapshot().sampleCount)
    }

    @Test
    fun ringWrapReplacesOldestIntervalDeterministically() {
        val metrics = metrics(capacity = 3)
        recordIntervals(metrics, listOf(100L, 200L, 300L, 400L))
        val snapshot = metrics.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(300L, snapshot.p50FrameIntervalNs)
        assertEquals(400L, snapshot.p95FrameIntervalNs)
        assertEquals(1_000_000_000.0 / 300.0, requireNotNull(snapshot.movingAverageFps), 0.00001)
    }

    @Test
    fun newestIntervalsContinueReplacingOldestAfterMultipleWraps() {
        val metrics = metrics(capacity = 2)
        recordIntervals(metrics, listOf(10L, 20L, 30L, 40L, 50L))
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.sampleCount)
        assertEquals(40L, snapshot.p50FrameIntervalNs)
        assertEquals(50L, snapshot.p95FrameIntervalNs)
    }

    @Test
    fun sampleCountNeverExceedsCapacity() {
        val metrics = metrics(capacity = 4)
        recordIntervals(metrics, List(100) { it.toLong() + 1L })
        assertEquals(4, metrics.snapshot().sampleCount)
    }

    @Test
    fun p50NearestRankForOddCountIsExact() {
        val metrics = metrics(capacity = 3)
        recordIntervals(metrics, listOf(10L, 20L, 30L))
        assertEquals(20L, metrics.snapshot().p50FrameIntervalNs)
    }

    @Test
    fun p50NearestRankForEvenCountUsesLowerMiddleRank() {
        val metrics = metrics(capacity = 4)
        recordIntervals(metrics, listOf(10L, 20L, 30L, 40L))
        assertEquals(20L, metrics.snapshot().p50FrameIntervalNs)
    }

    @Test
    fun p95NearestRankForTwoSamplesSelectsSecondRank() {
        val metrics = metrics(capacity = 2)
        recordIntervals(metrics, listOf(10L, 100L))
        assertEquals(100L, metrics.snapshot().p95FrameIntervalNs)
    }

    @Test
    fun p95NearestRankForFullRingSelectsExpectedTail() {
        val metrics = metrics(capacity = 5)
        recordIntervals(metrics, listOf(10L, 20L, 30L, 40L, 50L))
        assertEquals(50L, metrics.snapshot().p95FrameIntervalNs)
    }

    @Test
    fun skewedIntervalsHaveDeterministicP50AndP95() {
        val metrics = metrics(capacity = 5)
        recordIntervals(metrics, listOf(10L, 10L, 10L, 1_000L, 10_000L))
        val snapshot = metrics.snapshot()
        assertEquals(10L, snapshot.p50FrameIntervalNs)
        assertEquals(10_000L, snapshot.p95FrameIntervalNs)
    }

    @Test
    fun movingAverageUsesOnlyCurrentlyRetainedWindow() {
        val metrics = metrics(capacity = 2)
        recordIntervals(metrics, listOf(10L, 20L, 1_000L))
        val snapshot = metrics.snapshot()
        val expected = 1_000_000_000.0 / 510.0
        assertEquals(expected, requireNotNull(snapshot.movingAverageFps), 0.00001)
    }

    @Test
    fun snapshotDoesNotMutateRingOrOverwriteOrderState() {
        val metrics = metrics(capacity = 3)
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(200L)
        metrics.recordSensorTimestamp(400L)
        metrics.recordSensorTimestamp(700L)
        val before = metrics.snapshot()
        metrics.snapshot()
        metrics.recordSensorTimestamp(1_100L)
        val after = metrics.snapshot()
        assertEquals(3, before.sampleCount)
        assertEquals(3, after.sampleCount)
        assertEquals(300L, after.p50FrameIntervalNs)
        assertEquals(400L, after.p95FrameIntervalNs)
    }

    @Test
    fun repeatedSnapshotsWithoutNewSamplesAreIdentical() {
        val metrics = metrics(capacity = 4)
        recordIntervals(metrics, listOf(100L, 200L, 300L, 400L))
        val snapshot = metrics.snapshot()
        repeat(5) { assertEquals(snapshot, metrics.snapshot()) }
    }

    @Test
    fun requestedAndResolvedMeasurementIdentityIsRetained() {
        val originalRequest = PreviewFpsRequest(true, 24, 30)
        val originalResolved = CameraFpsCapability(15, 30)
        val metrics = PreviewFrameMetrics(originalRequest, originalResolved, 2)
        val laterRequest = originalRequest.copy(requestedMinimum = 30, requestedMaximum = 60)
        val laterResolved = CameraFpsCapability(30, 60)
        recordIntervals(metrics, listOf(100L))
        val snapshot = metrics.snapshot()
        assertEquals(originalRequest, snapshot.requested)
        assertEquals(originalResolved, snapshot.resolved)
        assertTrue(snapshot.requested != laterRequest)
        assertTrue(snapshot.resolved != laterResolved)
    }

    @Test
    fun veryLargePositiveTimestampDifferenceStaysPositiveAndFinite() {
        val metrics = metrics(capacity = 2)
        metrics.recordSensorTimestamp(1L)
        metrics.recordSensorTimestamp(Long.MAX_VALUE)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.sampleCount)
        assertEquals(Long.MAX_VALUE - 1L, snapshot.p50FrameIntervalNs)
        val fps = requireNotNull(snapshot.movingAverageFps)
        assertTrue(fps.isFinite())
        assertTrue(fps > 0.0)
    }

    @Test
    fun generatedTimestampMatrixKeepsBoundedPositiveFiniteDeterministicSnapshots() {
        for (capacity in listOf(2, 3, 8)) {
            val metrics = metrics(capacity = capacity)
            val timestamps = listOf(
                0L, -1L, 100L, 100L, 90L, 200L, 350L, 350L, 700L, 1_100L,
                1_600L, 2_200L, 2_900L, 3_700L, 4_600L,
            )
            for (timestamp in timestamps) {
                metrics.recordSensorTimestamp(timestamp)
                val snapshot = metrics.snapshot()
                assertTrue(snapshot.sampleCount <= capacity)
                if (snapshot.sampleCount == 0) {
                    assertNull(snapshot.movingAverageFps)
                    assertNull(snapshot.p50FrameIntervalNs)
                    assertNull(snapshot.p95FrameIntervalNs)
                } else {
                    val fps = requireNotNull(snapshot.movingAverageFps)
                    assertTrue(fps.isFinite() && fps > 0.0)
                    assertTrue(requireNotNull(snapshot.p50FrameIntervalNs) > 0L)
                    assertTrue(requireNotNull(snapshot.p95FrameIntervalNs) > 0L)
                }
                assertEquals(snapshot, metrics.snapshot())
            }
        }
    }

    private fun metrics(capacity: Int = 120): PreviewFrameMetrics =
        PreviewFrameMetrics(requested, resolved, capacity)

    private fun assertEmpty(metrics: PreviewFrameMetrics) {
        val snapshot = metrics.snapshot()
        assertEquals(0, snapshot.sampleCount)
        assertNull(snapshot.movingAverageFps)
        assertNull(snapshot.p50FrameIntervalNs)
        assertNull(snapshot.p95FrameIntervalNs)
    }

    private fun recordIntervals(metrics: PreviewFrameMetrics, intervals: List<Long>) {
        var timestamp = 1_000_000_000L
        metrics.recordSensorTimestamp(timestamp)
        for (interval in intervals) {
            require(interval > 0L)
            timestamp += interval
            metrics.recordSensorTimestamp(timestamp)
        }
    }
}
