package com.sahidcode404.camx.core.camera.model

enum class CameraStartupMilestone {
    PROCESS_START,
    ACTIVITY_CREATE,
    SURFACE_READY,
    HOT_CACHE_READY,
    OPEN_REQUESTED,
    CAMERA_OPENED,
    SESSION_CONFIG_REQUESTED,
    SESSION_CONFIGURED,
    FIRST_CAPTURE_RESULT,
    FIRST_PREVIEW_FRAME,
    PREVIEW_STABLE,
    LENS_SWITCH_REQUEST,
    LENS_SWITCH_NEW_FIRST_FRAME,
    SHUTTER_PRESS,
    RAW_SESSION_READY,
    RAW_REQUEST,
    RAW_RESULT,
    RAW_IMAGE,
    RAW_PAIR,
    DNG_WRITE_START,
    DNG_WRITE_END,
    PREVIEW_RESTORED,
}

data class CameraStartupTraceEvent(
    val milestone: CameraStartupMilestone,
    val elapsedRealtimeNs: Long,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
)

data class CameraStartupTrace(val events: List<CameraStartupTraceEvent>)

data class CameraResourceSnapshot(
    val cameraDevices: Int = 0,
    val captureSessions: Int = 0,
    val ownedSurfaces: Int = 0,
    val imageReaders: Int = 0,
    val openImages: Int = 0,
    val nativeImages: Int = 0,
    val hardwareBuffers: Int = 0,
    val nativeBufferBytes: Long = 0L,
    val cameraWorkers: Int = 0,
    val nativeQueueDepth: Int = 0,
    val jniGlobalReferences: Int = 0,
)
