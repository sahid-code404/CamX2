package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFpsResolverTest {
    private val mixedRanges = listOf(
        CameraFpsCapability(15, 30),
        CameraFpsCapability(30, 30),
        CameraFpsCapability(30, 60),
    )

    @Test
    fun overrideOffLeavesRangeAbsentEvenWhenRequestedValuesAreUnusual() {
        val result = resolve(PreviewFpsRequest(false, Int.MIN_VALUE, Int.MAX_VALUE))
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.OVERRIDE_DISABLED, result.reason)
    }

    @Test
    fun exactFixedRequestSelectsExactAdvertisedValue() {
        val result = resolve(PreviewFpsRequest(true, 30, 30))
        assertEquals(CameraFpsCapability(30, 30), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun exactVariableRequestSelectsExactAdvertisedValue() {
        val result = resolve(PreviewFpsRequest(true, 30, 60))
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun nearestFixedRangeUsesEndpointDistance() {
        val fixed = listOf(CameraFpsCapability(15, 15), CameraFpsCapability(30, 30), CameraFpsCapability(60, 60))
        val result = resolve(PreviewFpsRequest(true, 24, 24), ranges = fixed)
        assertEquals(CameraFpsCapability(30, 30), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE, result.reason)
    }

    @Test
    fun nearestVariableRangeUsesEndpointDistance() {
        val variable = listOf(CameraFpsCapability(10, 30), CameraFpsCapability(15, 30), CameraFpsCapability(30, 60))
        val result = resolve(PreviewFpsRequest(true, 20, 50), ranges = variable)
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE, result.reason)
    }

    @Test
    fun requestBelowAllAdvertisedRangesSelectsNearestAdvertisedRange() {
        val result = resolve(PreviewFpsRequest(true, 1, 5))
        assertEquals(CameraFpsCapability(15, 30), result.resolvedRange)
    }

    @Test
    fun requestAboveAllAdvertisedRangesSelectsNearestAdvertisedRange() {
        val result = resolve(PreviewFpsRequest(true, 90, 120))
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
    }

    @Test
    fun invertedRequestIsTypedInvalidAndDoesNotThrow() {
        assertInvalid(PreviewFpsRequest(true, 60, 30))
    }

    @Test
    fun zeroMinimumIsTypedInvalid() {
        assertInvalid(PreviewFpsRequest(true, 0, 30))
    }

    @Test
    fun zeroMaximumIsTypedInvalid() {
        assertInvalid(PreviewFpsRequest(true, 1, 0))
    }

    @Test
    fun negativeMinimumIsTypedInvalid() {
        assertInvalid(PreviewFpsRequest(true, -1, 30))
    }

    @Test
    fun negativeMaximumIsTypedInvalid() {
        assertInvalid(PreviewFpsRequest(true, 1, -30))
    }

    @Test
    fun emptyAdvertisedRangesReturnNoReportedRanges() {
        val result = resolve(PreviewFpsRequest(true, 30, 30), ranges = emptyList())
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.NO_REPORTED_RANGES, result.reason)
    }

    @Test
    fun duplicateAdvertisedRangesDoNotChangeResolution() {
        val duplicated = listOf(
            CameraFpsCapability(30, 60),
            CameraFpsCapability(15, 30),
            CameraFpsCapability(30, 60),
            CameraFpsCapability(30, 30),
            CameraFpsCapability(15, 30),
        )
        assertEquals(resolve(PreviewFpsRequest(true, 20, 45)), resolve(PreviewFpsRequest(true, 20, 45), ranges = duplicated))
    }

    @Test
    fun advertisedRangePermutationCannotChangeResolution() {
        val request = PreviewFpsRequest(true, 20, 45)
        val baseline = resolve(request)
        for (permutation in permutations(mixedRanges)) {
            assertEquals(baseline, resolve(request, ranges = permutation))
        }
    }

    @Test
    fun fixedOnlyCapabilitySetIsSupported() {
        val fixed = listOf(CameraFpsCapability(24, 24), CameraFpsCapability(30, 30), CameraFpsCapability(60, 60))
        assertEquals(CameraFpsCapability(24, 24), resolve(PreviewFpsRequest(true, 23, 24), ranges = fixed).resolvedRange)
    }

    @Test
    fun variableOnlyCapabilitySetIsSupported() {
        val variable = listOf(CameraFpsCapability(10, 24), CameraFpsCapability(15, 30), CameraFpsCapability(30, 60))
        assertEquals(CameraFpsCapability(15, 30), resolve(PreviewFpsRequest(true, 15, 30), ranges = variable).resolvedRange)
    }

    @Test
    fun mixedFixedAndVariableCapabilitySetIsSupported() {
        assertEquals(CameraFpsCapability(30, 30), resolve(PreviewFpsRequest(true, 30, 30)).resolvedRange)
    }

    @Test
    fun knownDurationThatSupportsRequestKeepsExactRange() {
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = 16_666_666L)
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun durationThatCannotSustainRequestedMaximumForcesLowerAdvertisedFallback() {
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = 33_333_333L)
        assertEquals(CameraFpsCapability(30, 30), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT, result.reason)
    }

    @Test
    fun durationLimitedFallbackNeverInventsIntermediateRange() {
        val ranges = listOf(CameraFpsCapability(15, 24), CameraFpsCapability(24, 30), CameraFpsCapability(30, 60))
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = 33_333_333L, ranges = ranges)
        assertEquals(CameraFpsCapability(24, 30), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT, result.reason)
        assertTrue(result.resolvedRange in ranges)
    }

    @Test
    fun durationThatEliminatesEveryRangeReturnsCadenceLimit() {
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = 40_000_000L)
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT, result.reason)
    }

    @Test
    fun nullDurationIsUnknownAndDoesNotFilterAdvertisedRange() {
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = null)
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun zeroDurationIsUnknownAndDoesNotFilterAdvertisedRange() {
        val result = resolve(PreviewFpsRequest(true, 30, 60), durationNs = 0L)
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun unrelatedCadenceFilteredRangeDoesNotMislabelNearestFallback() {
        val result = resolve(PreviewFpsRequest(true, 20, 30), durationNs = 33_333_333L)
        assertEquals(CameraFpsCapability(15, 30), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE, result.reason)
    }

    @Test
    fun deterministicTieBreakPrefersCloserMaximumThenHigherRange() {
        val tied = listOf(CameraFpsCapability(15, 30), CameraFpsCapability(30, 45))
        val result = resolve(PreviewFpsRequest(true, 30, 30), ranges = tied)
        assertEquals(CameraFpsCapability(15, 30), result.resolvedRange)
    }

    @Test
    fun extremeValidIntegerRequestDoesNotOverflowDistanceMath() {
        val extreme = listOf(
            CameraFpsCapability(1, 1),
            CameraFpsCapability(Int.MAX_VALUE - 1, Int.MAX_VALUE),
        )
        val request = PreviewFpsRequest(true, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val result = resolve(request, ranges = extreme)
        assertEquals(extreme[1], result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun pathologicalAdvertisedRangeCountFailsClosedBeforeSorting() {
        val ranges = List(MAX_PREVIEW_FPS_RANGES + 1) { index -> CameraFpsCapability(index + 1, index + 1) }
        val result = resolve(PreviewFpsRequest(true, 30, 30), ranges = ranges)
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.INVALID_REQUEST, result.reason)
    }

    @Test
    fun generatedMatrixIsDeterministicAndAlwaysReturnsAdvertisedValueOrNull() {
        val capabilitySets = listOf(
            listOf(CameraFpsCapability(15, 15), CameraFpsCapability(30, 30)),
            listOf(CameraFpsCapability(10, 24), CameraFpsCapability(15, 30), CameraFpsCapability(30, 60)),
            mixedRanges + CameraFpsCapability(30, 60),
        )
        val requests = listOf(
            PreviewFpsRequest(false, 30, 60),
            PreviewFpsRequest(true, -1, 30),
            PreviewFpsRequest(true, 1, 5),
            PreviewFpsRequest(true, 15, 30),
            PreviewFpsRequest(true, 20, 45),
            PreviewFpsRequest(true, 30, 60),
            PreviewFpsRequest(true, 120, 240),
        )
        val durations = listOf<Long?>(null, 0L, 16_666_666L, 33_333_333L, 50_000_000L)
        for (ranges in capabilitySets) {
            for (request in requests) {
                for (duration in durations) {
                    val baseline = resolve(request, duration, ranges)
                    repeat(3) { assertEquals(baseline, resolve(request, duration, ranges)) }
                    for (permutation in permutations(ranges.distinct())) {
                        val result = resolve(request, duration, permutation)
                        assertTrue(result.resolvedRange == null || result.resolvedRange in ranges)
                        if (request.requestedMinimum <= 0 || request.requestedMaximum <= 0 || request.requestedMaximum < request.requestedMinimum) {
                            if (request.overrideEnabled) assertEquals(PreviewFpsFallbackReason.INVALID_REQUEST, result.reason)
                        }
                        assertEquals(baseline, result)
                    }
                }
            }
        }
    }

    private fun assertInvalid(request: PreviewFpsRequest) {
        val result = resolve(request)
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.INVALID_REQUEST, result.reason)
    }

    private fun resolve(
        request: PreviewFpsRequest,
        durationNs: Long? = null,
        ranges: List<CameraFpsCapability> = mixedRanges,
    ): PreviewFpsResolution = PreviewFpsResolver.resolve(request, ranges, durationNs)

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.size <= 1) return listOf(values)
        val result = ArrayList<List<T>>()
        for (index in values.indices) {
            val head = values[index]
            val tail = values.filterIndexed { tailIndex, _ -> tailIndex != index }
            for (permutation in permutations(tail)) result += listOf(head) + permutation
        }
        return result.distinct()
    }
}
