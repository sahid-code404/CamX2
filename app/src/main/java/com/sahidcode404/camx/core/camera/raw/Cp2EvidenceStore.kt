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

/** Development-only, app-private, single-latest CP2 calibration manifest. */
internal class Cp2EvidenceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "cp2")
    private val json = Json { prettyPrint = true }

    suspend fun persist(bundle: Cp2CalibrationBundle): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            root.mkdirs()
            val staging = File(root, ".latest-${System.nanoTime()}")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Unable to create CP2 evidence staging directory" }
            try {
                File(staging, "manifest.json").writeText(
                    json.encodeToString(
                        kotlinx.serialization.json.JsonElement.serializer(),
                        manifest(bundle),
                    ),
                    StandardCharsets.UTF_8,
                )
                val latest = File(root, "latest")
                latest.deleteRecursively()
                check(staging.renameTo(latest)) { "Unable to publish latest CP2 evidence" }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }.isSuccess
    }

    private fun manifest(bundle: Cp2CalibrationBundle) = buildJsonObject {
        put("schema", "camx2-cp2-v1")
        put("status", if (bundle.report.success) "SUCCESS" else "INCOMPLETE")
        put("report", reportJson(bundle.report))
        bundle.staticObservation?.let { put("static", staticJson(it)) } ?: put("static", JsonNull)
        put("frames", buildJsonArray {
            bundle.bindings.forEach { binding ->
                add(buildJsonObject {
                    put("ordinal", binding.ordinal)
                    put("sensorTimestampNs", binding.sensorTimestampNs)
                    put("sourceCanonicalSha256", binding.sourceCanonicalSha256)
                    put("exactResultBound", binding.exactResultBound)
                    binding.observation?.let { dynamic ->
                        put("dynamicBlackLevels", doubleArrayJson(dynamic.dynamicBlackLevels))
                        dynamic.dynamicWhiteLevel?.let { put("dynamicWhiteLevel", it) }
                            ?: put("dynamicWhiteLevel", JsonNull)
                        put("noiseProfile", buildJsonArray {
                            dynamic.noiseProfile?.forEach { coefficient ->
                                add(buildJsonObject {
                                    put("shotSlope", coefficient.shotSlope)
                                    put("readVariance", coefficient.readVariance)
                                })
                            }
                        })
                    } ?: put("dynamic", JsonNull)
                })
            }
        })
    }

    private fun reportJson(report: Cp2CalibrationReport) = buildJsonObject {
        put("success", report.success)
        put("fusionNoiseReady", report.fusionNoiseReady)
        put("directM5ProfileReady", report.directM5ProfileReady)
        put("requestedFrames", report.requestedFrames)
        put("exactDynamicBindings", report.exactDynamicBindings)
        put("noiseProfileFrames", report.noiseProfileFrames)
        put("dynamicBlackLevelFrames", report.dynamicBlackLevelFrames)
        put("dynamicWhiteLevelFrames", report.dynamicWhiteLevelFrames)
        put("staticIdentityMatches", report.staticIdentityMatches)
        put("bayerCfaSupported", report.bayerCfaSupported)
        put("activeArrayPresent", report.activeArrayPresent)
        put("staticBlackLevelsPresent", report.staticBlackLevelsPresent)
        put("staticWhiteLevelPresent", report.staticWhiteLevelPresent)
        put("colorMatrixPairsPresent", report.colorMatrixPairsPresent)
        put("unboundOrdinals", buildJsonArray { report.unboundOrdinals.forEach { add(it) } })
        put("calibrationFingerprintSha256", report.calibrationFingerprintSha256)
        put("evidencePersisted", report.evidencePersisted)
        put("m5Blocker", "Camera2 exposes shot/read noise but no fixed-pattern-noise term; no value is invented")
    }

    private fun staticJson(static: Cp2StaticCalibrationObservation) = buildJsonObject {
        put("canonicalLensFingerprint", static.canonicalLensFingerprint.value)
        put("cameraProfileFingerprint", static.cameraProfileFingerprint.value)
        put("cameraRouteId", static.routeId.value)
        put("rawWidth", static.rawSize.width)
        put("rawHeight", static.rawSize.height)
        static.cfaArrangement?.let { put("cfaArrangement", it) } ?: put("cfaArrangement", JsonNull)
        put("activeArray", rectJson(static.activeArray))
        put("preCorrectionActiveArray", rectJson(static.preCorrectionActiveArray))
        put("blackLevels", buildJsonArray { static.blackLevels?.forEach { add(it) } })
        static.whiteLevel?.let { put("whiteLevel", it) } ?: put("whiteLevel", JsonNull)
        static.referenceIlluminant1?.let { put("referenceIlluminant1", it) }
            ?: put("referenceIlluminant1", JsonNull)
        static.referenceIlluminant2?.let { put("referenceIlluminant2", it) }
            ?: put("referenceIlluminant2", JsonNull)
        put("colorTransform1", matrixJson(static.colorTransform1))
        put("colorTransform2", matrixJson(static.colorTransform2))
        put("calibrationTransform1", matrixJson(static.calibrationTransform1))
        put("calibrationTransform2", matrixJson(static.calibrationTransform2))
        put("forwardMatrix1", matrixJson(static.forwardMatrix1))
        put("forwardMatrix2", matrixJson(static.forwardMatrix2))
    }

    private fun rectJson(rect: Cp2RectEvidence?) = rect?.let {
        buildJsonObject {
            put("left", it.left)
            put("top", it.top)
            put("right", it.right)
            put("bottom", it.bottom)
            put("width", it.width)
            put("height", it.height)
        }
    } ?: JsonNull

    private fun matrixJson(matrix: Cp2Matrix3x3Evidence?) = matrix?.let {
        buildJsonArray {
            it.values.forEach { rational ->
                add(buildJsonObject {
                    put("numerator", rational.numerator)
                    put("denominator", rational.denominator)
                })
            }
        }
    } ?: JsonNull

    private fun doubleArrayJson(values: List<Double>?) = buildJsonArray {
        values?.forEach { add(it) }
    }
}
