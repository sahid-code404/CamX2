package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAsyncOwnershipTest {
    @Test
    fun pendingOpenAThenSelectBDefersLateACloseAndLeavesBIntentUntouched() {
        val owner = CameraAsyncOwnership()
        val a = identity("a", selectionGeneration = 1, sessionGeneration = 1, surface = 1)
        val b = identity("b", selectionGeneration = 2, sessionGeneration = 2, surface = 1)
        owner.publishIntent(a)
        val pendingA = owner.begin(PendingCameraStage.OPEN)
        owner.publishIntent(b)
        val pendingB = owner.begin(PendingCameraStage.OPEN)
        val staleA = delivery("device-a")

        val staleResolution = owner.resolveResource(pendingA, staleA.lease)
        val staleCleanup = requiredStaleCleanup(staleResolution)
        assertEquals(0, staleA.resource.closeCount)
        assertEquals(b, owner.authoritativeIntent())
        assertTrue(staleCleanup.closeOnce())
        assertEquals(1, staleA.resource.closeCount)

        val currentB = delivery("device-b")
        val adoption = owner.resolveResource(pendingB, currentB.lease)
        assertTrue(adoption is ResourceAdoption.Adopted)
        assertSame(currentB.resource, (adoption as ResourceAdoption.Adopted).resource)
        assertEquals(0, currentB.resource.closeCount)
    }

    @Test
    fun pendingConfigureAThenSelectBDefersLateSessionCloseAndLeavesBUntouched() {
        val owner = CameraAsyncOwnership()
        owner.publishIntent(identity("a", 1, 1, 1))
        val pendingA = owner.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        val b = identity("b", 2, 2, 1)
        owner.publishIntent(b)
        val staleSession = delivery("session-a")

        val staleCleanup = requiredStaleCleanup(owner.resolveResource(pendingA, staleSession.lease))
        assertEquals(0, staleSession.resource.closeCount)
        assertEquals(b, owner.authoritativeIntent())
        assertTrue(staleCleanup.closeOnce())
        assertEquals(1, staleSession.resource.closeCount)
    }

    @Test
    fun rapidAToBToCAdoptsOnlyCForEveryCallbackOrder() {
        permutations(listOf("a", "b", "c")).forEach { order ->
            val owner = CameraAsyncOwnership()
            val permits = linkedMapOf<String, PendingCameraOperationPermit>()
            listOf("a", "b", "c").forEachIndexed { index, name ->
                owner.publishIntent(
                    identity(
                        suffix = name,
                        selectionGeneration = index + 1,
                        sessionGeneration = index + 1,
                        surface = 1,
                    ),
                )
                permits[name] = owner.begin(PendingCameraStage.OPEN)
            }
            val deliveries = listOf("a", "b", "c").associateWith(::delivery)
            val adopted = mutableListOf<String>()

            order.forEach { name ->
                when (val result = owner.resolveResource(permits.getValue(name), deliveries.getValue(name).lease)) {
                    is ResourceAdoption.Adopted -> adopted += result.resource.name
                    is ResourceAdoption.Stale -> result.cleanup?.closeOnce()
                }
            }

            assertEquals(listOf("c"), adopted)
            assertEquals(1, deliveries.getValue("a").resource.closeCount)
            assertEquals(1, deliveries.getValue("b").resource.closeCount)
            assertEquals(0, deliveries.getValue("c").resource.closeCount)
            assertEquals("route:c", owner.authoritativeIntent()?.selection?.routeId?.value)
        }
    }

    @Test
    fun pauseInvalidatesPendingOpenConfigureAndFirstFrameImmediately() {
        listOf(PendingCameraStage.OPEN, PendingCameraStage.PREVIEW_CONFIGURATION).forEach { stage ->
            val owner = CameraAsyncOwnership()
            owner.publishIntent(identity("a", 1, 1, 1))
            val pending = owner.begin(stage)
            owner.invalidatePending()
            val delivered = delivery(stage.name)

            val cleanup = requiredStaleCleanup(owner.resolveResource(pending, delivered.lease))
            assertEquals(0, delivered.resource.closeCount)
            assertTrue(cleanup.closeOnce())
            assertEquals(1, delivered.resource.closeCount)
            assertNull(owner.authoritativeIntent())
        }

        val owner = CameraAsyncOwnership()
        owner.publishIntent(identity("a", 1, 1, 1))
        val firstFrame = owner.begin(PendingCameraStage.FIRST_FRAME)
        owner.invalidatePending()
        assertEquals(CameraCallbackDecision.STALE, owner.completeSignal(firstFrame))
        assertNull(owner.authoritativeIntent())
    }

    @Test
    fun shutdownInvalidatesPendingOpenConfigureAndQueuedCallbacksAndIsTerminal() {
        listOf(PendingCameraStage.OPEN, PendingCameraStage.PREVIEW_CONFIGURATION).forEach { stage ->
            val owner = CameraAsyncOwnership()
            owner.publishIntent(identity("a", 1, 1, 1))
            val pending = owner.begin(stage)
            assertTrue(owner.shutdown())
            val delivered = delivery(stage.name)

            val cleanup = requiredStaleCleanup(owner.resolveResource(pending, delivered.lease))
            assertEquals(0, delivered.resource.closeCount)
            assertTrue(cleanup.closeOnce())
            assertEquals(1, delivered.resource.closeCount)
            assertTrue(!owner.shutdown())
            assertThrows(IllegalStateException::class.java) {
                owner.publishIntent(identity("b", 2, 2, 1))
            }
        }
    }

    @Test
    fun replacingSurfaceRejectsOldSessionWithoutRevokingNewSurfaceIntent() {
        val owner = CameraAsyncOwnership()
        val oldSurface = identity("a", 1, 1, surface = 1)
        val newSurface = oldSurface.copy(surface = PreviewSurfaceIdentity(2))
        owner.publishIntent(oldSurface)
        val pendingOld = owner.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        owner.publishIntent(newSurface)
        val oldSession = delivery("old-surface-session")

        val cleanup = requiredStaleCleanup(owner.resolveResource(pendingOld, oldSession.lease))
        assertEquals(0, oldSession.resource.closeCount)
        assertEquals(newSurface, owner.authoritativeIntent())
        assertTrue(cleanup.closeOnce())
        assertEquals(1, oldSession.resource.closeCount)
    }

    @Test
    fun onlyCurrentFirstFramePermitCanVerifyAndDuplicateFrameIsStale() {
        val owner = CameraAsyncOwnership()
        owner.publishIntent(identity("a", 1, 1, 1))
        val oldFrame = owner.begin(PendingCameraStage.FIRST_FRAME)
        owner.publishIntent(identity("a", 1, 2, 1))
        val currentFrame = owner.begin(PendingCameraStage.FIRST_FRAME)

        assertEquals(CameraCallbackDecision.STALE, owner.completeSignal(oldFrame))
        assertEquals(CameraCallbackDecision.ACCEPTED, owner.completeSignal(currentFrame))
        assertEquals(CameraCallbackDecision.STALE, owner.completeSignal(currentFrame))
    }

    @Test
    fun staleDeliveryDetachesCleanupOnceAndDuplicateCallbackGetsNoCloseAuthority() {
        val owner = CameraAsyncOwnership()
        owner.publishIntent(identity("a", 1, 1, 1))
        val pending = owner.begin(PendingCameraStage.OPEN)
        owner.publishIntent(identity("b", 2, 2, 1))
        val stale = delivery("stale")

        val first = owner.resolveResource(pending, stale.lease)
        val firstCleanup = requiredStaleCleanup(first)
        val duplicate = owner.resolveResource(pending, stale.lease)
        assertTrue(duplicate is ResourceAdoption.Stale)
        assertNull((duplicate as ResourceAdoption.Stale).cleanup)
        assertEquals(0, stale.resource.closeCount)
        assertEquals(CloseOnceCameraResource.Disposition.STALE_DETACHED, stale.lease.disposition())

        assertTrue(firstCleanup.closeOnce())
        assertFalse(firstCleanup.closeOnce())
        assertEquals(1, stale.resource.closeCount)
        assertEquals(CloseOnceCameraResource.Disposition.STALE_CLOSED, stale.lease.disposition())
    }

    @Test
    fun adoptedDeliveryCannotBeAdoptedTwiceOrClosedByDuplicateCallback() {
        val owner = CameraAsyncOwnership()
        owner.publishIntent(identity("a", 1, 1, 1))
        val pending = owner.begin(PendingCameraStage.OPEN)
        val current = delivery("current")

        val first = owner.resolveResource(pending, current.lease)
        assertTrue(first is ResourceAdoption.Adopted)
        val duplicate = owner.resolveResource(pending, current.lease)
        assertTrue(duplicate is ResourceAdoption.Stale)
        assertNull((duplicate as ResourceAdoption.Stale).cleanup)
        assertEquals(0, current.resource.closeCount)
        assertEquals(CloseOnceCameraResource.Disposition.ADOPTED, current.lease.disposition())
    }

    @Test
    fun requestedAndBaselineConfigCallbacksCannotMutateLaterSelection() {
        val owner = CameraAsyncOwnership()
        owner.publishIntent(
            identity("a", 1, 1, 1, PreviewConfigurationAttemptKind.REQUESTED),
        )
        val requested = owner.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        owner.publishIntent(
            identity("a", 1, 2, 1, PreviewConfigurationAttemptKind.SAFE_BASELINE),
        )
        val baseline = owner.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        val c = identity("c", 2, 3, 1, PreviewConfigurationAttemptKind.REQUESTED)
        owner.publishIntent(c)
        val requestedSession = delivery("requested")
        val baselineSession = delivery("baseline")

        val requestedCleanup = requiredStaleCleanup(owner.resolveResource(requested, requestedSession.lease))
        val baselineCleanup = requiredStaleCleanup(owner.resolveResource(baseline, baselineSession.lease))
        assertEquals(0, requestedSession.resource.closeCount)
        assertEquals(0, baselineSession.resource.closeCount)
        assertEquals(c, owner.authoritativeIntent())
        assertTrue(requestedCleanup.closeOnce())
        assertTrue(baselineCleanup.closeOnce())
        assertEquals(1, requestedSession.resource.closeCount)
        assertEquals(1, baselineSession.resource.closeCount)
    }

    @Test
    fun cleanupPlanAttemptsEveryCloseOnceAndSuppressesLaterFailures() {
        val calls = mutableListOf<String>()
        val first = CameraResourceCleanup {
            calls += "first"
            throw IllegalStateException("first failure")
        }
        val second = CameraResourceCleanup {
            calls += "second"
            throw IllegalArgumentException("second failure")
        }
        val third = CameraResourceCleanup { calls += "third" }
        val plan = CameraCleanupPlan(listOf(first, second, third))

        val failure = assertThrows(IllegalStateException::class.java) { plan.closeAllOnce() }
        assertEquals(listOf("first", "second", "third"), calls)
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IllegalArgumentException)
        assertFalse(plan.closeAllOnce())
        assertEquals(listOf("first", "second", "third"), calls)
    }

    @Test
    fun failedStaleCloseIsStillAttemptedOnlyOnce() {
        var closeAttempts = 0
        val resource = CloseOnceCameraResource("failing") {
            closeAttempts += 1
            throw IllegalStateException("close failed")
        }
        val cleanup = checkNotNull(resource.detachForStaleCleanup())

        assertThrows(IllegalStateException::class.java) { cleanup.closeOnce() }
        assertFalse(cleanup.closeOnce())
        assertEquals(1, closeAttempts)
        assertEquals(CloseOnceCameraResource.Disposition.STALE_CLOSE_FAILED, resource.disposition())
    }

    private fun identity(
        suffix: String,
        selectionGeneration: Int,
        sessionGeneration: Int,
        surface: Int,
        attempt: PreviewConfigurationAttemptKind = PreviewConfigurationAttemptKind.REQUESTED,
    ) = CameraOperationIdentity(
        selection = ActiveCameraSelection(
            canonicalLensFingerprint = CanonicalLensFingerprint("lens:$suffix"),
            profileFingerprint = CameraProfileFingerprint("profile:$suffix"),
            routeId = CameraRouteId("route:$suffix"),
            selectionGeneration = SelectionGeneration(selectionGeneration.toLong()),
            sessionGeneration = SessionGeneration(sessionGeneration.toLong()),
        ),
        surface = PreviewSurfaceIdentity(surface.toLong()),
        previewAttempt = attempt,
    )

    private fun requiredStaleCleanup(result: ResourceAdoption<FakeResource>): CameraResourceCleanup {
        assertTrue(result is ResourceAdoption.Stale)
        val cleanup = (result as ResourceAdoption.Stale).cleanup
        assertNotNull(cleanup)
        return checkNotNull(cleanup)
    }

    private fun delivery(name: String): Delivery {
        val resource = FakeResource(name)
        return Delivery(resource, CloseOnceCameraResource(resource, FakeResource::close))
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.size <= 1) {
        listOf(values)
    } else {
        values.flatMap { selected ->
            permutations(values - selected).map { remainder -> listOf(selected) + remainder }
        }
    }

    private data class Delivery(
        val resource: FakeResource,
        val lease: CloseOnceCameraResource<FakeResource>,
    )

    private class FakeResource(val name: String) {
        var closeCount: Int = 0
            private set

        fun close() {
            closeCount += 1
        }
    }
}
