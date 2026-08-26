package com.sahidcode404.camx.core.camera.diagnostics

const val CAMX_APPLICATION_BASELINE_API = 23

enum class OptionalNativeCapability(
    val minimumApi: Int,
    val publicLibrary: String,
    val requiredSymbols: Set<String>,
) {
    CAMERA_NDK_METADATA(
        minimumApi = 24,
        publicLibrary = "libcamera2ndk.so",
        requiredSymbols = setOf(
            "ACameraManager_delete",
            "ACameraManager_deleteCameraIdList",
            "ACameraMetadata_free",
        ),
    ),
    MEDIA_IMAGE_OWNERSHIP(
        minimumApi = 24,
        publicLibrary = "libmediandk.so",
        requiredSymbols = setOf("AImage_delete", "AImageReader_delete"),
    ),
    HARDWARE_BUFFER_OWNERSHIP(
        minimumApi = 26,
        publicLibrary = "libnativewindow.so",
        requiredSymbols = setOf("AHardwareBuffer_release"),
    ),
}

sealed interface NativeCapabilityAvailability

data object Available : NativeCapabilityAvailability

data class UnavailableBecauseApiLevel(
    val deviceApi: Int,
    val requiredApi: Int,
) : NativeCapabilityAvailability

data class UnavailableBecauseLibrary(
    val library: String,
) : NativeCapabilityAvailability

data class UnavailableBecauseSymbol(
    val library: String,
    val symbol: String,
) : NativeCapabilityAvailability

data object Unsupported : NativeCapabilityAvailability

interface NativeCapabilityProbe {
    fun libraryAvailable(library: String): Boolean

    fun symbolAvailable(library: String, symbol: String): Boolean
}

data class CameraPlatformCapabilitySnapshot(
    val applicationSupported: Boolean,
    val javaCamera2IsAuthoritativeControlPlane: Boolean,
    val optionalNative: Map<OptionalNativeCapability, NativeCapabilityAvailability>,
)

/** Pure policy only. CAMX-100A intentionally ships no optional native backend or dynamic loader. */
object NativeCapabilityPolicy {
    fun baseline(deviceApi: Int): CameraPlatformCapabilitySnapshot {
        require(deviceApi > 0) { "Android API level must be positive" }
        return CameraPlatformCapabilitySnapshot(
            applicationSupported = deviceApi >= CAMX_APPLICATION_BASELINE_API,
            javaCamera2IsAuthoritativeControlPlane = deviceApi >= CAMX_APPLICATION_BASELINE_API,
            optionalNative = OptionalNativeCapability.entries.associateWith { capability ->
                assess(
                    capability = capability,
                    deviceApi = deviceApi,
                    implementedCapabilities = emptySet(),
                    probe = null,
                )
            },
        )
    }

    fun assess(
        capability: OptionalNativeCapability,
        deviceApi: Int,
        implementedCapabilities: Set<OptionalNativeCapability>,
        probe: NativeCapabilityProbe?,
    ): NativeCapabilityAvailability {
        require(deviceApi > 0) { "Android API level must be positive" }
        if (deviceApi < capability.minimumApi) {
            return UnavailableBecauseApiLevel(deviceApi, capability.minimumApi)
        }
        if (capability !in implementedCapabilities) return Unsupported

        val requiredProbe = checkNotNull(probe) {
            "An implemented optional native capability requires an explicit public-library probe"
        }
        if (!requiredProbe.libraryAvailable(capability.publicLibrary)) {
            return UnavailableBecauseLibrary(capability.publicLibrary)
        }
        val missingSymbol = capability.requiredSymbols
            .sorted()
            .firstOrNull { symbol -> !requiredProbe.symbolAvailable(capability.publicLibrary, symbol) }
        return if (missingSymbol == null) {
            Available
        } else {
            UnavailableBecauseSymbol(capability.publicLibrary, missingSymbol)
        }
    }
}
