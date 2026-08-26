package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CameraResourceCounters {
    private val cameraDevices = AtomicInteger()
    private val captureSessions = AtomicInteger()
    private val surfaces = AtomicInteger()
    private val imageReaders = AtomicInteger()
    private val images = AtomicInteger()
    private val nativeImages = AtomicInteger()
    private val hardwareBuffers = AtomicInteger()
    private val nativeBytes = AtomicLong()
    private val cameraWorkers = AtomicInteger()
    private val nativeQueueDepth = AtomicInteger()
    private val jniGlobalReferences = AtomicInteger()

    fun increment(kind: ResourceKind, amount: Long = 1L) {
        require(amount >= 0L) { "Resource increment cannot be negative" }
        update(kind, amount)
    }

    fun decrement(kind: ResourceKind, amount: Long = 1L) {
        require(amount >= 0L) { "Resource decrement cannot be negative" }
        update(kind, -amount)
    }

    fun snapshot() = CameraResourceSnapshot(
        cameraDevices = cameraDevices.get(),
        captureSessions = captureSessions.get(),
        ownedSurfaces = surfaces.get(),
        imageReaders = imageReaders.get(),
        openImages = images.get(),
        nativeImages = nativeImages.get(),
        hardwareBuffers = hardwareBuffers.get(),
        nativeBufferBytes = nativeBytes.get(),
        cameraWorkers = cameraWorkers.get(),
        nativeQueueDepth = nativeQueueDepth.get(),
        jniGlobalReferences = jniGlobalReferences.get(),
    )

    private fun update(kind: ResourceKind, delta: Long) {
        when (kind) {
            ResourceKind.CAMERA_DEVICE -> cameraDevices.updateNonNegative(delta)
            ResourceKind.CAPTURE_SESSION -> captureSessions.updateNonNegative(delta)
            ResourceKind.SURFACE -> surfaces.updateNonNegative(delta)
            ResourceKind.IMAGE_READER -> imageReaders.updateNonNegative(delta)
            ResourceKind.IMAGE -> images.updateNonNegative(delta)
            ResourceKind.NATIVE_IMAGE -> nativeImages.updateNonNegative(delta)
            ResourceKind.HARDWARE_BUFFER -> hardwareBuffers.updateNonNegative(delta)
            ResourceKind.CAMERA_WORKER -> cameraWorkers.updateNonNegative(delta)
            ResourceKind.NATIVE_QUEUE_ENTRY -> nativeQueueDepth.updateNonNegative(delta)
            ResourceKind.JNI_GLOBAL_REFERENCE -> jniGlobalReferences.updateNonNegative(delta)
            ResourceKind.NATIVE_BUFFER_BYTES -> {
                nativeBytes.updateNonNegative(delta)
            }
        }
    }

    private fun AtomicInteger.updateNonNegative(delta: Long) {
        require(delta in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Resource counter delta exceeds integer bounds"
        }
        while (true) {
            val current = get()
            val updated = current.toLong() + delta
            check(updated in 0L..Int.MAX_VALUE.toLong()) { "Resource counter overflow or underflow" }
            if (compareAndSet(current, updated.toInt())) return
        }
    }

    private fun AtomicLong.updateNonNegative(delta: Long) {
        while (true) {
            val current = get()
            val updated = Math.addExact(current, delta)
            check(updated >= 0L) { "Resource counter underflow" }
            if (compareAndSet(current, updated)) return
        }
    }
}

enum class ResourceKind {
    CAMERA_DEVICE,
    CAPTURE_SESSION,
    SURFACE,
    IMAGE_READER,
    IMAGE,
    NATIVE_IMAGE,
    HARDWARE_BUFFER,
    NATIVE_BUFFER_BYTES,
    CAMERA_WORKER,
    NATIVE_QUEUE_ENTRY,
    JNI_GLOBAL_REFERENCE,
}
