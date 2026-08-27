package com.sahidcode404.camx.core.camera.raw

import java.util.concurrent.atomic.AtomicReference

/**
 * Optional, one-listener observation seam for immutable metadata extraction from accepted M4 results.
 * The listener never owns Camera2 resources and any observer failure is isolated from RAW pairing.
 */
internal object RawBurstResultObservationHub {
    private val listener = AtomicReference<((Long, Int, Any) -> Unit)?>(null)

    fun install(observer: (timestampNs: Long, ordinal: Int, result: Any) -> Unit): AutoCloseable {
        check(listener.compareAndSet(null, observer)) { "A RAW burst result observer is already installed" }
        return AutoCloseable { listener.compareAndSet(observer, null) }
    }

    fun observe(timestampNs: Long, ordinal: Int, result: Any) {
        val current = listener.get() ?: return
        runCatching { current(timestampNs, ordinal, result) }
    }
}
