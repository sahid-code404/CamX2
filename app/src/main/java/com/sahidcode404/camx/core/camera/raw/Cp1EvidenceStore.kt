package com.sahidcode404.camx.core.camera.raw

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Development-only, app-private and bounded CP1 acquisition evidence. */
internal class Cp1EvidenceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "cp1")
    private val json = Json { prettyPrint = true }

    suspend fun persistSuccess(
        frameSet: ImmutableRawFrameSet,
        report: RawBurstCaptureReport,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            check(report.success) { "CP1 success evidence requires a successful acquisition report" }
            check(frameSet.frames.size == report.requestedFrames) { "CP1 frame/report membership diverged" }
            root.mkdirs()
            val staging = File(root, ".latest-${System.nanoTime()}")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Unable to create CP1 evidence staging directory" }
            try {
                frameSet.frames.forEach { frame ->
                    val output = File(staging, "frame-${frame.ordinal.toString().padStart(2, '0')}.raw16")
                    output.outputStream().buffered().use(frame::writeCanonicalRaster)
                    check(output.length() == frame.canonicalByteCount) {
                        "CP1 evidence frame byte count diverged from immutable RAW evidence"
                    }
                }
                File(staging, "manifest.json").writeText(
                    json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), successManifest(frameSet, report)),
                    StandardCharsets.UTF_8,
                )
                val latest = File(root, "latest")
                latest.deleteRecursively()
                check(staging.renameTo(latest)) { "Unable to publish latest CP1 success evidence" }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }.isSuccess
    }

    suspend fun persistFailure(report: RawBurstCaptureReport): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            root.mkdirs()
            val target = File(root, "latest-failed-report.json")
            val staging = File(root, ".latest-failed-${System.nanoTime()}.json")
            staging.writeText(
                json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reportJson(report)),
                StandardCharsets.UTF_8,
            )
            if (target.exists()) check(target.delete()) { "Unable to replace previous CP1 failure report" }
            check(staging.renameTo(target)) { "Unable to publish latest CP1 failure report" }
        }.isSuccess
    }

    private fun successManifest(
        frameSet: ImmutableRawFrameSet,
        report: RawBurstCaptureReport,
    ) = buildJsonObject {
        put("schema", "camx2-cp1-v1")
        put("status", "SUCCESS")
        put("report", reportJson(report))
        put("frames", buildJsonArray {
            frameSet.frames.forEach { frame ->
                add(buildJsonObject {
                    put("ordinal", frame.ordinal)
                    put("sensorTimestampNs", frame.metadata.sensorTimestampNs)
                    put("frameNumber", frame.metadata.frameNumber)
                    frame.metadata.exposureTimeNs?.let { put("exposureTimeNs", it) } ?: put("exposureTimeNs", JsonNull)
                    frame.metadata.sensitivityIso?.let { put("sensitivityIso", it) } ?: put("sensitivityIso", JsonNull)
                    frame.metadata.frameDurationNs?.let { put("frameDurationNs", it) } ?: put("frameDurationNs", JsonNull)
                    put("rawWidth", frame.rawSize.width)
                    put("rawHeight", frame.rawSize.height)
                    put("sourceRowStrideBytes", frame.sourceRowStrideBytes)
                    put("sourcePixelStrideBytes", frame.sourcePixelStrideBytes)
                    put("sourceRequiredBytes", frame.sourceRequiredBytes)
                    put("canonicalByteCount", frame.canonicalByteCount)
                    put("canonicalSha256", frame.canonicalSha256)
                })
            }
        })
    }

    private fun reportJson(report: RawBurstCaptureReport) = buildJsonObject {
        put("success", report.success)
        put("requestedFrames", report.requestedFrames)
        put("preflightFrames", report.preflightFrames)
        put("captureRequestsSubmitted", report.captureRequestsSubmitted)
        put("imagesReceived", report.imagesReceived)
        put("resultsReceived", report.resultsReceived)
        put("exactPairsCreated", report.exactPairsCreated)
        put("framesCopied", report.framesCopied)
        put("framesAccepted", report.framesAccepted)
        put("duplicateImageTimestamps", report.duplicateImageTimestamps)
        put("duplicateResultTimestamps", report.duplicateResultTimestamps)
        put("duplicateOrdinals", report.duplicateOrdinals)
        put("unmatchedImages", report.unmatchedImages)
        put("unmatchedResults", report.unmatchedResults)
        put("invalidImages", report.invalidImages)
        put("captureFailures", report.captureFailures)
        put("sequenceAborted", report.sequenceAborted)
        put("timedOut", report.timedOut)
        put("cancelled", report.cancelled)
        put("staleCallbacksAccepted", report.staleCallbacksAccepted)
        put("imageObjectsStillOwned", report.imageObjectsStillOwned)
        report.failureDetail?.let { put("failureDetail", it) } ?: put("failureDetail", JsonNull)
        report.identity?.let { identity ->
            put("identity", buildJsonObject {
                put("captureToken", identity.captureToken.value)
                put("selectionGeneration", identity.selectionGeneration.value)
                put("sessionGeneration", identity.sessionGeneration.value)
                put("canonicalLensFingerprint", identity.canonicalLensFingerprint.value)
                put("cameraProfileFingerprint", identity.cameraProfileFingerprint.value)
                put("cameraRouteId", identity.routeId.value)
                put("rawWidth", identity.rawSize.width)
                put("rawHeight", identity.rawSize.height)
                put("displayRotation", identity.displayRotationAtShutter.name)
            })
        } ?: put("identity", JsonNull)
        report.preflight?.let { preflight ->
            put("preflight", buildJsonObject {
                put("captureToken", preflight.captureToken.value)
                put("selectionGeneration", preflight.selectionGeneration.value)
                put("sessionGeneration", preflight.sessionGeneration.value)
                put("canonicalLensFingerprint", preflight.canonicalLensFingerprint.value)
                put("cameraProfileFingerprint", preflight.cameraProfileFingerprint.value)
                put("cameraRouteId", preflight.routeId.value)
                put("rawWidth", preflight.rawSize.width)
                put("rawHeight", preflight.rawSize.height)
                put("imageFormat", preflight.imageFormat)
                put("rowStrideBytes", preflight.rowStrideBytes)
                put("pixelStrideBytes", preflight.pixelStrideBytes)
                put("sourceRequiredBytes", preflight.sourceRequiredBytes)
            })
        } ?: put("preflight", JsonNull)
    }
}
