package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCacheState
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicy
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicyInput
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanReason
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanState
import com.sahidcode404.camx.core.camera.topology.ReconciliationCompletion
import com.sahidcode404.camx.core.camera.topology.ReconciliationRequestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepRescanCoordinatorTest {
    @Test
    fun `rescan is unavailable before first verified frame`() {
        var requested = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { false },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = {
                requested += 1
                ReconciliationRequestResult.STARTED
            },
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )
        assertEquals(DeepRescanRequestResult.NOT_READY, coordinator.requestDeepRescan())
        assertEquals(0, requested)
    }

    @Test
    fun `rescan is unavailable until coherent inventory is ready`() {
        var requested = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            inventoryReady = { false },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = {
                requested += 1
                ReconciliationRequestResult.STARTED
            },
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )
        assertEquals(DeepRescanRequestResult.NOT_READY, coordinator.requestDeepRescan())
        assertEquals(0, requested)
    }

    @Test
    fun `active rescan rejects a second request and forwards coherent completion`() {
        var forced = false
        var completion: ((ReconciliationCompletion) -> Unit)? = null
        var requests = 0
        var starts = 0
        val finishes = mutableListOf<ReconciliationCompletion>()
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = { forced = it },
            requestReconciliation = { done ->
                requests += 1
                completion = done
                ReconciliationRequestResult.STARTED
            },
            onRescanStarted = { starts += 1 },
            onRescanFinished = finishes::add,
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )
        assertEquals(DeepRescanRequestResult.STARTED, coordinator.requestDeepRescan())
        assertTrue(forced)
        assertEquals(1, starts)
        assertEquals(DeepRescanRequestResult.ALREADY_RUNNING, coordinator.requestDeepRescan())
        assertEquals(1, requests)
        checkNotNull(completion).invoke(ReconciliationCompletion.COMPLETE)
        assertEquals(listOf(ReconciliationCompletion.COMPLETE), finishes)
        assertFalse(forced)
        assertFalse(coordinator.operationActive())
    }

    @Test
    fun `incomplete completion is forwarded and releases operation`() {
        var completion: ((ReconciliationCompletion) -> Unit)? = null
        val finishes = mutableListOf<ReconciliationCompletion>()
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = { done ->
                completion = done
                ReconciliationRequestResult.STARTED
            },
            onRescanFinished = finishes::add,
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )

        assertEquals(DeepRescanRequestResult.STARTED, coordinator.requestDeepRescan())
        checkNotNull(completion).invoke(ReconciliationCompletion.INCOMPLETE)

        assertEquals(listOf(ReconciliationCompletion.INCOMPLETE), finishes)
        assertFalse(coordinator.operationActive())
    }

    @Test
    fun `rejected reconciliation cancels prepared inventory refresh`() {
        val finishes = mutableListOf<ReconciliationCompletion>()
        var forced = false
        var starts = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = { forced = it },
            requestReconciliation = { ReconciliationRequestResult.NOT_ARMED },
            onRescanStarted = { starts += 1 },
            onRescanFinished = finishes::add,
            resetCaches = { DiscoveryCacheResetResult.NOTHING_TO_RESET },
        )

        assertEquals(DeepRescanRequestResult.NOT_READY, coordinator.requestDeepRescan())
        assertEquals(1, starts)
        assertEquals(listOf(ReconciliationCompletion.CANCELLED), finishes)
        assertFalse(forced)
        assertFalse(coordinator.operationActive())
    }

    @Test
    fun `explicit rescan overrides stable empty skip and warm hot policy`() {
        val stableEmpty = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(
                cacheState = DeepAuxCacheState.COMPATIBLE,
                cachedAdvertisedTopologySignature = "same",
                currentAdvertisedTopologySignature = "same",
                previousFullReconciliationComplete = true,
                explicitDeepRescan = true,
            ),
        )
        val warmHot = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(
                cacheState = DeepAuxCacheState.COMPATIBLE,
                cachedAdvertisedTopologySignature = "same",
                currentAdvertisedTopologySignature = "same",
                cachedSuccessfulDeepIds = listOf("opaque-hidden"),
                previousFullReconciliationComplete = true,
                explicitDeepRescan = true,
            ),
        )
        listOf(stableEmpty, warmHot).forEach { decision ->
            assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
            assertEquals(DeepAuxScanReason.EXPLICIT_RESCAN, decision.reason)
        }
    }

    @Test
    fun `cache reset is separate and rejected while reconciliation is active`() = runTest {
        var resetCalls = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { true },
            setExplicitDeepRescan = {},
            requestReconciliation = { ReconciliationRequestResult.STARTED },
            resetCaches = {
                resetCalls += 1
                DiscoveryCacheResetResult.SUCCESS
            },
        )
        assertEquals(DiscoveryCacheResetResult.FAILED, coordinator.resetDiscoveryCache())
        assertEquals(0, resetCalls)
    }

    @Test
    fun `cache reset does not imply a deep rescan`() = runTest {
        var rescanRequests = 0
        var resetCalls = 0
        val coordinator = DeepRescanCoordinator(
            firstFrameVerified = { true },
            reconciliationRunning = { false },
            setExplicitDeepRescan = {},
            requestReconciliation = {
                rescanRequests += 1
                ReconciliationRequestResult.STARTED
            },
            resetCaches = {
                resetCalls += 1
                DiscoveryCacheResetResult.SUCCESS
            },
        )
        assertEquals(DiscoveryCacheResetResult.SUCCESS, coordinator.resetDiscoveryCache())
        assertEquals(1, resetCalls)
        assertEquals(0, rescanRequests)
    }
}
