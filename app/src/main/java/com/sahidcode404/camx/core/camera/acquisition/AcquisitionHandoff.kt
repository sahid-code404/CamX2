package com.sahidcode404.camx.core.camera.acquisition

import java.util.concurrent.atomic.AtomicReference

/**
 * Opaque one-shot permit. Only the issuing [AcquisitionHandoffGate] can accept it.
 */
class AcquisitionHandoffPermit internal constructor(
    internal val ownerIdentity: Any,
    internal val sequence: Long,
    internal val expected: AcquisitionPermitIdentity,
)

enum class AcquisitionRejectionReason {
    STALE_PERMIT,
    IDENTITY_MISMATCH,
}

sealed interface AcquisitionTransfer<out T : AutoCloseable> {
    data class Accepted<T : AutoCloseable>(val lease: AcquisitionLease<T>) : AcquisitionTransfer<T>
    data class Rejected(
        val reason: AcquisitionRejectionReason,
        val closeFailure: Throwable?,
    ) : AcquisitionTransfer<Nothing>
}

/** Move-only ownership of a transferred acquisition payload. */
class AcquisitionLease<T : AutoCloseable> internal constructor(
    val identity: AcquisitionIdentity,
    payload: T,
) : AutoCloseable {
    private val ownedPayload = AtomicReference<T?>(payload)

    fun take(): T = checkNotNull(ownedPayload.getAndSet(null)) {
        "Acquisition payload ownership was already transferred or closed"
    }

    override fun close() {
        ownedPayload.getAndSet(null)?.close()
    }
}

/**
 * Generation/permit-bound exact-once handoff gate. This class owns no Camera2 object and performs no
 * disk, compression, graph, or scientific work. Rejected payloads are closed exactly once by the
 * transfer call after the gate's synchronized state decision has completed.
 */
class AcquisitionHandoffGate {
    private val ownerIdentity = Any()
    private var nextSequence = 0L
    private var pending: AcquisitionHandoffPermit? = null

    @Synchronized
    fun begin(expected: AcquisitionPermitIdentity): AcquisitionHandoffPermit {
        check(nextSequence < Long.MAX_VALUE) { "Acquisition handoff sequence exhausted" }
        return AcquisitionHandoffPermit(
            ownerIdentity = ownerIdentity,
            sequence = ++nextSequence,
            expected = expected,
        ).also { pending = it }
    }

    @Synchronized
    fun invalidate() {
        pending = null
    }

    fun <T : AutoCloseable> transfer(
        permit: AcquisitionHandoffPermit,
        identity: AcquisitionIdentity,
        payload: T,
    ): AcquisitionTransfer<T> {
        val rejection = synchronized(this) {
            when {
                permit.ownerIdentity !== ownerIdentity || pending !== permit ->
                    AcquisitionRejectionReason.STALE_PERMIT
                permit.expected != identity.permitIdentity() -> {
                    pending = null
                    AcquisitionRejectionReason.IDENTITY_MISMATCH
                }
                else -> {
                    pending = null
                    null
                }
            }
        }
        if (rejection == null) {
            return AcquisitionTransfer.Accepted(AcquisitionLease(identity, payload))
        }
        val closeFailure = runCatching { payload.close() }.exceptionOrNull()
        return AcquisitionTransfer.Rejected(rejection, closeFailure)
    }
}
