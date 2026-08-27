package com.sahidcode404.camx.core.camera.raw

import android.content.Context
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionReport
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Development-only, app-private, single-latest CP3 fusion evidence. No CP4 DNG is written here. */
internal class Cp3EvidenceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "cp3")
    private val json = Json { prettyPrint = true }

    suspend fun persist(report: Cp3FusionReport): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            root.mkdirs()
            val staging = File(root, ".latest-${System.nanoTime()}")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Unable to create CP3 evidence staging directory" }
            try {
                File(staging, "manifest.json").writeText(
                    json.encodeToString(
                        kotlinx.serialization.json.JsonElement.serializer(),
                        manifest(report),
                    ),
                    StandardCharsets.UTF_8,
                )
                val latest = File(root, "latest")
                latest.deleteRecursively()
                check(staging.renameTo(latest)) { "Unable to publish latest CP3 evidence" }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }.isSuccess
    }

    private fun manifest(report: Cp3FusionReport) = buildJsonObject {
        put("schema", "camx2-cp3-v1")
        put("status", if (report.success) "FUSED" else "FAILED")
        put("algorithmId", report.algorithmId)
        put("algorithmVersion", report.algorithmVersion)
        put("requestedFrames", report.requestedFrames)
        report.referenceOrdinal?.let { put("referenceOrdinal", it) } ?: put("referenceOrdinal", JsonNull)
        put("exposureIdentityFrames", report.exposureIdentityFrames)
        put("alignedFrames", report.alignedFrames)
        put("contributingFrames", report.contributingFrames)
        put("activePixelCount", report.activePixelCount)
        put("multiFramePixelCount", report.multiFramePixelCount)
        put("referenceOnlyPixelCount", report.referenceOnlyPixelCount)
        put("censoredPixelCount", report.censoredPixelCount)
        put("rejectedPixelMeasurements", report.rejectedPixelMeasurements)
        put("calibrationFingerprintSha256", report.calibrationFingerprintSha256)
        report.outputSha256?.let { put("outputSha256", it) } ?: put("outputSha256", JsonNull)
        put("fixedPatternNoiseMode", report.fixedPatternNoiseMode.name)
        put("noiseSemantics", "Camera2 SENSOR_NOISE_PROFILE N(x)^2=S*x+O with normalized x; converted to DN^2")
        put("cp4NegativeWritten", false)
        report.failureDetail?.let { put("failureDetail", it) } ?: put("failureDetail", JsonNull)
        put("sourceCanonicalSha256", buildJsonArray {
            report.sourceCanonicalSha256.forEach { add(it) }
        })
        put("includedOrdinals", buildJsonArray {
            report.includedOrdinals.forEach { add(it) }
        })
        put("frames", buildJsonArray {
            report.frameEvidence.forEach { frame ->
                add(buildJsonObject {
                    put("ordinal", frame.ordinal)
                    put("decision", frame.decision.name)
                    put("dxPixels", frame.dxPixels)
                    put("dyPixels", frame.dyPixels)
                    put("meanNormalizedSquaredResidual", frame.meanNormalizedSquaredResidual)
                    frame.secondBestMeanNormalizedSquaredResidual?.let {
                        put("secondBestMeanNormalizedSquaredResidual", it)
                    } ?: put("secondBestMeanNormalizedSquaredResidual", JsonNull)
                    put("sampledPairs", frame.sampledPairs)
                    put("inlierFraction", frame.inlierFraction)
                })
            }
        })
    }
}
