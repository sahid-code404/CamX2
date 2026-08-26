package com.sahidcode404.camx.core.camera.diagnostics

import android.os.Build

data class NativeCoreSnapshot(
    val schema: Long,
    val androidApi: Long,
    val compiledApi: Long,
    val pointerBits: Long,
    val counters: LongArray,
)

object NativeCore {
    private val loaded = runCatching { System.loadLibrary("camx_core") }.isSuccess
    val availability: NativeCapabilityAvailability = if (loaded) {
        Available
    } else {
        UnavailableBecauseLibrary("libcamx_core.so")
    }

    fun snapshotOrNull(): NativeCoreSnapshot? {
        if (!loaded) return null
        val deviceApi = Build.VERSION.SDK_INT
        return NativeCoreSnapshotDecoder.decode(
            values = runCatching { nativeSnapshot(deviceApi) }.getOrNull(),
            expectedAndroidApi = deviceApi,
        )
    }

    private external fun nativeSnapshot(androidApi: Int): LongArray?
}

/** Pure validation boundary for the coarse JNI protocol. */
internal object NativeCoreSnapshotDecoder {
    fun decode(values: LongArray?, expectedAndroidApi: Int): NativeCoreSnapshot? {
        if (values == null || values.size != SNAPSHOT_SIZE) return null
        if (expectedAndroidApi < CAMX_APPLICATION_BASELINE_API) return null
        if (values[SCHEMA_INDEX] != EXPECTED_SCHEMA) return null
        if (values[ANDROID_API_INDEX] != expectedAndroidApi.toLong()) return null
        if (values[COMPILED_API_INDEX] != CAMX_APPLICATION_BASELINE_API.toLong()) return null
        if (values[POINTER_BITS_INDEX] !in VALID_POINTER_WIDTHS) return null

        val counters = values.copyOfRange(HEADER_SIZE, SNAPSHOT_SIZE)
        if (counters.any { it < 0L }) return null
        return NativeCoreSnapshot(
            schema = values[SCHEMA_INDEX],
            androidApi = values[ANDROID_API_INDEX],
            compiledApi = values[COMPILED_API_INDEX],
            pointerBits = values[POINTER_BITS_INDEX],
            counters = counters,
        )
    }

    private const val EXPECTED_SCHEMA = 2L
    private const val HEADER_SIZE = 4
    private const val COUNTER_COUNT = 6
    private const val SNAPSHOT_SIZE = HEADER_SIZE + COUNTER_COUNT
    private const val SCHEMA_INDEX = 0
    private const val ANDROID_API_INDEX = 1
    private const val COMPILED_API_INDEX = 2
    private const val POINTER_BITS_INDEX = 3
    private val VALID_POINTER_WIDTHS = setOf(32L, 64L)
}
