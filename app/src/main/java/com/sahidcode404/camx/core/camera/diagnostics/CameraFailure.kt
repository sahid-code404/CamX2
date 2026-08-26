package com.sahidcode404.camx.core.camera.diagnostics

enum class CameraFailureCategory {
    PERMISSION,
    AVAILABILITY,
    DEVICE,
    SURFACE,
    SESSION,
    PREVIEW,
    RAW,
    STORAGE,
    CONCURRENCY,
    CONTROL,
}

enum class TrustChange {
    NONE,
    MARK_TEMPORARILY_UNAVAILABLE,
    REJECT_PREVIEW_PROFILE,
    REJECT_RAW_PROFILE,
}

data class CameraFailurePolicy(
    val category: CameraFailureCategory,
    val structural: Boolean,
    val trustChange: TrustChange,
    val fallbackPermitted: Boolean,
    val automaticRetryPermitted: Boolean,
    val sameCanonicalFailoverPermitted: Boolean,
    val userActionRequired: Boolean,
) {
    init {
        require(!sameCanonicalFailoverPermitted || structural) {
            "Same-canonical failover requires a structural failure"
        }
        require(
            trustChange !in setOf(
                TrustChange.REJECT_PREVIEW_PROFILE,
                TrustChange.REJECT_RAW_PROFILE,
            ) || structural,
        ) { "Permanent trust rejection requires a structural failure" }
        require(!structural || !automaticRetryPermitted) {
            "A structural failure cannot blindly retry the same configuration"
        }
        require(!fallbackPermitted || (!structural && trustChange == TrustChange.NONE)) {
            "Configuration fallback cannot mutate persistent camera trust"
        }
    }
}

sealed interface CameraFailure {
    val policy: CameraFailurePolicy
}

data class PermissionDenied(val permanentlyDenied: Boolean) : CameraFailure {
    override val policy = policy(
        category = CameraFailureCategory.PERMISSION,
        userAction = permanentlyDenied,
    )
}

data object CameraInUse : CameraFailure {
    override val policy = transientAvailability()
}

data object MaximumCamerasInUse : CameraFailure {
    override val policy = transientAvailability()
}

data object CameraDisabled : CameraFailure {
    override val policy = policy(
        category = CameraFailureCategory.AVAILABILITY,
        trust = TrustChange.MARK_TEMPORARILY_UNAVAILABLE,
        userAction = true,
    )
}

data object CameraDisconnected : CameraFailure {
    override val policy = transientAvailability(category = CameraFailureCategory.DEVICE)
}

data class CameraDeviceError(val platformCode: Int, val structural: Boolean = false) : CameraFailure {
    override val policy = policy(
        category = CameraFailureCategory.DEVICE,
        structural = structural,
        trust = if (structural) TrustChange.REJECT_PREVIEW_PROFILE
        else TrustChange.MARK_TEMPORARILY_UNAVAILABLE,
        retry = !structural,
        failover = structural,
    )
}

data object OpenTimeout : CameraFailure {
    override val policy = transientAvailability(category = CameraFailureCategory.DEVICE)
}

data object SurfaceUnavailable : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.SURFACE, retry = true)
}

enum class RequestedConfigurationKind(val category: CameraFailureCategory) {
    FPS(CameraFailureCategory.PREVIEW),
    EXACT_FPS_RANGE(CameraFailureCategory.PREVIEW),
    HIGH_RESOLUTION_PREVIEW(CameraFailureCategory.PREVIEW),
    OPTIONAL_YUV_OUTPUT(CameraFailureCategory.SESSION),
    OPTIONAL_ANALYSIS_OUTPUT(CameraFailureCategory.SESSION),
    OPTIONAL_AUXILIARY_STREAM(CameraFailureCategory.SESSION),
    ASPECT_PREFERENCE(CameraFailureCategory.PREVIEW),
    ENHANCEMENT(CameraFailureCategory.SESSION),
}

data class RequestedConfigurationRejected(
    val requested: RequestedConfigurationKind,
) : CameraFailure {
    override val policy = policy(
        category = requested.category,
        fallback = true,
    )
}

data object SafeBaselineConfigurationRejected : CameraFailure {
    override val policy = structuralPreviewFailure(CameraFailureCategory.SESSION)
}

data object PreviewTimeout : CameraFailure {
    override val policy = transientAvailability(category = CameraFailureCategory.PREVIEW)
}

data object RawUnsupported : CameraFailure {
    override val policy = structuralRawFailure()
}

data object RawSessionRejected : CameraFailure {
    override val policy = structuralRawFailure()
}

data object RawCaptureTimeout : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.RAW, retry = true)
}

data object RawPairTimeout : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.RAW, retry = true)
}

data class DngWriteFailure(val reason: String) : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.STORAGE, userAction = true)
}

data class MediaStoreFailure(val reason: String) : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.STORAGE, userAction = true)
}

data object StaleSelection : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.CONCURRENCY)
}

data object StaleSession : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.CONCURRENCY)
}

data object StaleCapture : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.CONCURRENCY)
}

data object Cancelled : CameraFailure {
    override val policy = policy(category = CameraFailureCategory.CONTROL)
}

private fun policy(
    category: CameraFailureCategory,
    structural: Boolean = false,
    trust: TrustChange = TrustChange.NONE,
    fallback: Boolean = false,
    retry: Boolean = false,
    failover: Boolean = false,
    userAction: Boolean = false,
) = CameraFailurePolicy(
    category = category,
    structural = structural,
    trustChange = trust,
    fallbackPermitted = fallback,
    automaticRetryPermitted = retry,
    sameCanonicalFailoverPermitted = failover,
    userActionRequired = userAction,
)

private fun transientAvailability(
    category: CameraFailureCategory = CameraFailureCategory.AVAILABILITY,
) = policy(
    category = category,
    trust = TrustChange.MARK_TEMPORARILY_UNAVAILABLE,
    retry = true,
)

private fun structuralPreviewFailure(category: CameraFailureCategory) = policy(
    category = category,
    structural = true,
    trust = TrustChange.REJECT_PREVIEW_PROFILE,
    failover = true,
)

private fun structuralRawFailure() = policy(
    category = CameraFailureCategory.RAW,
    structural = true,
    trust = TrustChange.REJECT_RAW_PROFILE,
    failover = true,
)
