package com.sahidcode404.camx.core.camera.acquisition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AcquisitionHandoffTest {
    @Test
    fun acceptedPayloadTransfersExactlyOnceAndLeaseClosesExactlyOnce() {
        val gate = AcquisitionHandoffGate()
        val identity = acquisitionIdentity()
        val permit = gate.begin(identity.permitIdentity())
        val payload = CountingPayload()
        val transfer = gate.transfer(permit, identity, payload) as AcquisitionTransfer.Accepted
        transfer.lease.close()
        transfer.lease.close()
        assertEquals(1, payload.closeCount.get())
        assertThrows(IllegalStateException::class.java) { transfer.lease.take() }
    }

    @Test
    fun takingLeaseMovesOwnershipAwayFromLease() {
        val gate = AcquisitionHandoffGate()
        val identity = acquisitionIdentity()
        val payload = CountingPayload()
        val transfer = gate.transfer(
            gate.begin(identity.permitIdentity()),
            identity,
            payload,
        ) as AcquisitionTransfer.Accepted
        val moved = transfer.lease.take()
        transfer.lease.close()
        assertEquals(0, payload.closeCount.get())
        moved.close()
        assertEquals(1, payload.closeCount.get())
    }

    @Test
    fun invalidatedPermitClosesRejectedPayloadOnce() {
        val gate = AcquisitionHandoffGate()
        val identity = acquisitionIdentity()
        val permit = gate.begin(identity.permitIdentity())
        gate.invalidate()
        val payload = CountingPayload()
        val rejected = gate.transfer(permit, identity, payload) as AcquisitionTransfer.Rejected
        assertEquals(AcquisitionRejectionReason.STALE_PERMIT, rejected.reason)
        assertNull(rejected.closeFailure)
        assertEquals(1, payload.closeCount.get())
    }

    @Test
    fun staleDeliveryCannotConsumeNewerPermit() {
        val gate = AcquisitionHandoffGate()
        val oldIdentity = acquisitionIdentity(captureToken = 1L)
        val newIdentity = acquisitionIdentity(captureToken = 2L)
        val oldPermit = gate.begin(oldIdentity.permitIdentity())
        val newPermit = gate.begin(newIdentity.permitIdentity())
        val oldPayload = CountingPayload()
        val oldResult = gate.transfer(oldPermit, oldIdentity, oldPayload) as AcquisitionTransfer.Rejected
        assertEquals(AcquisitionRejectionReason.STALE_PERMIT, oldResult.reason)
        assertEquals(1, oldPayload.closeCount.get())
        val newPayload = CountingPayload()
        val newResult = gate.transfer(newPermit, newIdentity, newPayload)
        assertTrue(newResult is AcquisitionTransfer.Accepted)
        (newResult as AcquisitionTransfer.Accepted).lease.close()
        assertEquals(1, newPayload.closeCount.get())
    }

    @Test
    fun identityMismatchConsumesPermitAndClosesPayload() {
        val gate = AcquisitionHandoffGate()
        val expected = acquisitionIdentity(sessionGeneration = 3L)
        val wrong = acquisitionIdentity(sessionGeneration = 4L)
        val permit = gate.begin(expected.permitIdentity())
        val wrongPayload = CountingPayload()
        val mismatch = gate.transfer(permit, wrong, wrongPayload) as AcquisitionTransfer.Rejected
        assertEquals(AcquisitionRejectionReason.IDENTITY_MISMATCH, mismatch.reason)
        assertEquals(1, wrongPayload.closeCount.get())
        val second = CountingPayload()
        val stale = gate.transfer(permit, expected, second) as AcquisitionTransfer.Rejected
        assertEquals(AcquisitionRejectionReason.STALE_PERMIT, stale.reason)
        assertEquals(1, second.closeCount.get())
    }

    @Test
    fun concurrentDuplicateDeliveryAdoptsOnlyOnePayload() {
        val gate = AcquisitionHandoffGate()
        val identity = acquisitionIdentity()
        val permit = gate.begin(identity.permitIdentity())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val accepted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        repeat(2) {
            executor.execute {
                start.await()
                val result = gate.transfer(permit, identity, SharedCountingPayload(closeCount))
                when (result) {
                    is AcquisitionTransfer.Accepted -> {
                        accepted.incrementAndGet()
                        result.lease.close()
                    }
                    is AcquisitionTransfer.Rejected -> rejected.incrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, accepted.get())
        assertEquals(1, rejected.get())
        assertEquals(2, closeCount.get())
    }

    @Test
    fun rejectedCloseFailureIsClassifiedWithoutReopeningOwnership() {
        val gate = AcquisitionHandoffGate()
        val identity = acquisitionIdentity()
        val permit = gate.begin(identity.permitIdentity())
        gate.invalidate()
        val rejected = gate.transfer(permit, identity, ThrowingPayload()) as AcquisitionTransfer.Rejected
        assertNotNull(rejected.closeFailure)
        assertEquals(AcquisitionRejectionReason.STALE_PERMIT, rejected.reason)
    }

    private class CountingPayload : AutoCloseable {
        val closeCount = AtomicInteger(0)
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class SharedCountingPayload(private val counter: AtomicInteger) : AutoCloseable {
        override fun close() {
            counter.incrementAndGet()
        }
    }

    private class ThrowingPayload : AutoCloseable {
        override fun close() {
            throw IllegalStateException("synthetic close failure")
        }
    }
}
