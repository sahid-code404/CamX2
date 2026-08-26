package com.sahidcode404.camx.core.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilityPolicyTest {
    @Test
    fun api23KeepsApplicationAndJavaCamera2ValidWhenPost23NativeBackendsAreUnavailable() {
        val snapshot = NativeCapabilityPolicy.baseline(deviceApi = 23)

        assertTrue(snapshot.applicationSupported)
        assertTrue(snapshot.javaCamera2IsAuthoritativeControlPlane)
        assertEquals(
            UnavailableBecauseApiLevel(deviceApi = 23, requiredApi = 24),
            snapshot.optionalNative.getValue(OptionalNativeCapability.CAMERA_NDK_METADATA),
        )
        assertEquals(
            UnavailableBecauseApiLevel(deviceApi = 23, requiredApi = 24),
            snapshot.optionalNative.getValue(OptionalNativeCapability.MEDIA_IMAGE_OWNERSHIP),
        )
        assertEquals(
            UnavailableBecauseApiLevel(deviceApi = 23, requiredApi = 26),
            snapshot.optionalNative.getValue(OptionalNativeCapability.HARDWARE_BUFFER_OWNERSHIP),
        )
    }

    @Test
    fun unimplementedBackendIsTypedUnsupportedWithoutProbingOrChangingControlPlane() {
        val snapshot = NativeCapabilityPolicy.baseline(deviceApi = 37)

        assertTrue(snapshot.javaCamera2IsAuthoritativeControlPlane)
        assertTrue(snapshot.optionalNative.values.all { it == Unsupported })
    }

    @Test
    fun implementedBackendDistinguishesLibrarySymbolAndAvailableResults() {
        val capability = OptionalNativeCapability.MEDIA_IMAGE_OWNERSHIP
        val enabled = setOf(capability)
        assertEquals(
            UnavailableBecauseLibrary("libmediandk.so"),
            NativeCapabilityPolicy.assess(capability, 24, enabled, Probe(library = false)),
        )
        assertEquals(
            UnavailableBecauseSymbol("libmediandk.so", "AImageReader_delete"),
            NativeCapabilityPolicy.assess(
                capability,
                24,
                enabled,
                Probe(library = true, missingSymbol = "AImageReader_delete"),
            ),
        )
        assertEquals(
            Available,
            NativeCapabilityPolicy.assess(capability, 24, enabled, Probe(library = true)),
        )
    }

    @Test
    fun apiBelowApplicationBaselineIsNotPresentedAsSupported() {
        val snapshot = NativeCapabilityPolicy.baseline(deviceApi = 22)

        assertFalse(snapshot.applicationSupported)
        assertFalse(snapshot.javaCamera2IsAuthoritativeControlPlane)
    }

    private class Probe(
        private val library: Boolean,
        private val missingSymbol: String? = null,
    ) : NativeCapabilityProbe {
        override fun libraryAvailable(library: String): Boolean = this.library

        override fun symbolAvailable(library: String, symbol: String): Boolean = symbol != missingSymbol
    }
}
