package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.AndroidApkInspector
import com.sahidcode404.camx.core.update.verification.InstalledAppIdentity
import com.sahidcode404.camx.core.update.verification.VerifiedApkPromotion
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface UpdateRepository {
    val state: StateFlow<UpdateState>
    suspend fun checkAfterFirstFrame()
    suspend fun checkManually()
    suspend fun downloadAvailable()
    fun cancel()
    fun reportInstallFailure(code: UpdateFailureCode)
}

class FirstPreviewGate {
    private val verified = AtomicBoolean(false)

    fun markVerified() {
        verified.set(true)
    }

    fun requireVerified() {
        check(verified.get()) { "OTA cannot start before the first verified preview frame" }
    }
}

/**
 * Camera-facing integration is intentionally reduced to one boolean event. The update package never
 * owns or imports CameraSessionController, CameraRoute, CanonicalLens, or any camera resource.
 */
class FirstPreviewUpdateTrigger(
    private val gate: FirstPreviewGate,
    private val repository: UpdateRepository,
    private val scope: CoroutineScope,
) {
    private val automaticCheckStarted = AtomicBoolean(false)

    fun onFirstVerifiedFrame() {
        gate.markVerified()
        if (!automaticCheckStarted.compareAndSet(false, true)) return
        scope.launch {
            repository.checkAfterFirstFrame()
        }
    }
}

internal fun interface InstalledIdentitySource {
    fun read(): InstalledAppIdentity
}

internal fun interface DownloadedCandidateVerifier {
    suspend fun verify(candidate: File, manifest: DevOtaManifest): VerifiedApkPromotion
}

class DevelopmentUpdateRepository internal constructor(
    private val firstPreviewGate: FirstPreviewGate,
    private val installedIdentitySource: InstalledIdentitySource,
    private val httpClient: DevelopmentHttpClient,
    private val fileStore: UpdateFileStore,
    private val candidateVerifier: DownloadedCandidateVerifier,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxApkBytes: Long = com.sahidcode404.camx.core.update.verification.DevOtaTrust.MAX_APK_BYTES,
) : UpdateRepository, AutoCloseable {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val operationLock = Any()
    private val automaticCheckStarted = AtomicBoolean(false)
    private var activeJob: Job? = null
    private var operationGeneration = 0L

    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    init {
        try {
            fileStore.initialize()
        } catch (_: UpdateStorageException) {
            mutableState.value = UpdateState.Failed(UpdateFailureCode.STORAGE_IO)
        }
    }

    override suspend fun checkAfterFirstFrame() {
        firstPreviewGate.requireVerified()
        if (!automaticCheckStarted.compareAndSet(false, true)) return
        runExclusive(::performCheck)
    }

    override suspend fun checkManually() {
        runExclusive(::performCheck)
    }

    override suspend fun downloadAvailable() {
        val manifest = when (val current = mutableState.value) {
            is UpdateState.Available -> current.manifest
            is UpdateState.Failed -> current.manifest
            else -> null
        } ?: return
        runExclusive { generation -> performDownload(generation, manifest) }
    }

    override fun cancel() {
        val job = synchronized(operationLock) { activeJob }
        if (job == null) return
        job.cancel(CancellationException("Development update cancelled"))
        httpClient.cancelActive()
    }

    override fun reportInstallFailure(code: UpdateFailureCode) {
        val current = mutableState.value as? UpdateState.ReadyToInstall ?: return
        runCatching { fileStore.deleteVerifiedCandidate() }
        mutableState.value = UpdateState.Failed(code, current.manifest)
    }

    override fun close() {
        cancel()
        httpClient.cancelActive()
        runCatching { fileStore.deletePart() }
    }

    private suspend fun runExclusive(operation: suspend (Long) -> Unit) {
        val callerJob = checkNotNull(coroutineContext[Job]) {
            "Development update operation requires structured coroutine ownership"
        }
        val generation = synchronized(operationLock) {
            if (activeJob != null) return
            check(operationGeneration < Long.MAX_VALUE) { "Update operation generation exhausted" }
            operationGeneration += 1L
            activeJob = callerJob
            operationGeneration
        }

        try {
            withContext(ioDispatcher) {
                operation(generation)
            }
        } catch (_: CancellationException) {
            val cancelledManifest = (mutableState.value as? UpdateState.Downloading)?.manifest
            runCatching { fileStore.deletePart() }
            if (cancelledManifest != null) {
                runCatching { fileStore.deleteVerifiedCandidate() }
            }
            publish(
                generation,
                UpdateState.Failed(UpdateFailureCode.CANCELLED, cancelledManifest),
            )
        } catch (failure: UpdateOperationFailure) {
            runCatching { fileStore.deletePart() }
            publish(generation, UpdateState.Failed(failure.code, failure.manifest))
        } catch (_: IOException) {
            runCatching { fileStore.deletePart() }
            publish(generation, UpdateState.Failed(UpdateFailureCode.NETWORK))
        } finally {
            httpClient.cancelActive()
            synchronized(operationLock) {
                if (activeJob === callerJob) activeJob = null
            }
        }
    }

    private suspend fun performCheck(generation: Long) {
        publish(generation, UpdateState.Checking)
        val installed = try {
            installedIdentitySource.read()
        } catch (_: Exception) {
            throw UpdateOperationFailure(UpdateFailureCode.APK_INSPECTION_FAILED)
        }
        val manifest = fetchManifest()
        when (val checked = DevelopmentManifestValidator.validate(manifest, installed)) {
            is DevelopmentManifestCheck.Available -> publish(
                generation,
                UpdateState.Available(checked.manifest),
            )
            is DevelopmentManifestCheck.UpToDate -> publish(
                generation,
                UpdateState.UpToDate(checked.manifest),
            )
            is DevelopmentManifestCheck.Rejected -> throw UpdateOperationFailure(checked.code)
        }
    }

    private suspend fun fetchManifest(): DevOtaManifest {
        val response = try {
            httpClient.open(DevOtaEndpoints.MANIFEST_URL)
        } catch (failure: DevelopmentNetworkException) {
            coroutineContext.ensureActive()
            throw UpdateOperationFailure(failure.code)
        }
        response.use {
            val declared = response.contentLength
            if (declared != null && declared > DevelopmentManifestValidator.MAX_MANIFEST_BYTES) {
                throw UpdateOperationFailure(UpdateFailureCode.MANIFEST_TOO_LARGE)
            }
            val bytes = readBoundedManifest(response, declared)
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Exception) {
                throw UpdateOperationFailure(UpdateFailureCode.MALFORMED_MANIFEST)
            }
            return try {
                STRICT_JSON.decodeFromString<DevOtaManifest>(text)
            } catch (_: SerializationException) {
                throw UpdateOperationFailure(UpdateFailureCode.MALFORMED_MANIFEST)
            } catch (_: IllegalArgumentException) {
                throw UpdateOperationFailure(UpdateFailureCode.MALFORMED_MANIFEST)
            }
        }
    }

    private suspend fun readBoundedManifest(
        response: DevelopmentHttpResponse,
        declaredLength: Long?,
    ): ByteArray {
        val limit = DevelopmentManifestValidator.MAX_MANIFEST_BYTES
        val output = java.io.ByteArrayOutputStream(minOf(limit, 4 * 1024))
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val count = try {
                response.body.read(buffer)
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                throw UpdateOperationFailure(UpdateFailureCode.NETWORK)
            }
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) throw UpdateOperationFailure(UpdateFailureCode.MANIFEST_TOO_LARGE)
            output.write(buffer, 0, count)
        }
        if (declaredLength != null && total.toLong() < declaredLength) {
            throw UpdateOperationFailure(UpdateFailureCode.TRUNCATED_RESPONSE)
        }
        return output.toByteArray()
    }

    private suspend fun performDownload(
        generation: Long,
        manifest: DevOtaManifest,
    ) {
        val part = try {
            fileStore.preparePart()
        } catch (failure: UpdateStorageException) {
            throw UpdateOperationFailure(failure.code, manifest)
        }
        val response = try {
            httpClient.open(DevOtaEndpoints.APK_URL)
        } catch (failure: DevelopmentNetworkException) {
            coroutineContext.ensureActive()
            throw UpdateOperationFailure(failure.code, manifest)
        }

        try {
            response.use {
                val declared = response.contentLength
                if (declared != null && declared > maxApkBytes) {
                    throw UpdateOperationFailure(UpdateFailureCode.DOWNLOAD_TOO_LARGE, manifest)
                }
                val totalForProgress = declared?.takeIf { it >= 0L }
                publish(
                    generation,
                    UpdateState.Downloading(
                        manifest = manifest,
                        downloadedBytes = 0L,
                        totalBytes = totalForProgress,
                    ),
                )
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastProgressEmission = 0L
                FileOutputStream(part).use { fileOutput ->
                    val output = BufferedOutputStream(fileOutput, DOWNLOAD_BUFFER_BYTES)
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = try {
                            response.body.read(buffer)
                        } catch (error: IOException) {
                            coroutineContext.ensureActive()
                            throw UpdateOperationFailure(UpdateFailureCode.NETWORK, manifest)
                        }
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count.toLong()
                        if (downloaded > maxApkBytes) {
                            throw UpdateOperationFailure(UpdateFailureCode.DOWNLOAD_TOO_LARGE, manifest)
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (downloaded - lastProgressEmission >= PROGRESS_STEP_BYTES) {
                            lastProgressEmission = downloaded
                            publish(
                                generation,
                                UpdateState.Downloading(manifest, downloaded, totalForProgress),
                            )
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
                if (declared != null && downloaded < declared) {
                    throw UpdateOperationFailure(UpdateFailureCode.TRUNCATED_RESPONSE, manifest)
                }
                publish(
                    generation,
                    UpdateState.Downloading(manifest, downloaded, totalForProgress),
                )
                val actualDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                if (actualDigest != manifest.sha256.normalizedDevOtaSha256OrNull()) {
                    throw UpdateOperationFailure(UpdateFailureCode.SHA256_MISMATCH, manifest)
                }
            }
        } catch (failure: UpdateOperationFailure) {
            throw failure
        } catch (_: IOException) {
            throw UpdateOperationFailure(UpdateFailureCode.STORAGE_IO, manifest)
        }

        val promoted = try {
            fileStore.promotePart()
        } catch (failure: UpdateStorageException) {
            throw UpdateOperationFailure(failure.code, manifest)
        }
        val verified = try {
            candidateVerifier.verify(promoted, manifest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            runCatching { fileStore.deleteVerifiedCandidate() }
            throw UpdateOperationFailure(UpdateFailureCode.STORAGE_IO, manifest)
        } catch (_: Exception) {
            runCatching { fileStore.deleteVerifiedCandidate() }
            throw UpdateOperationFailure(UpdateFailureCode.APK_INSPECTION_FAILED, manifest)
        }
        when (verified) {
            is VerifiedApkPromotion.Ready -> publish(
                generation,
                UpdateState.ReadyToInstall(verified.apk, manifest),
            )
            is VerifiedApkPromotion.Rejected -> {
                runCatching { fileStore.deleteVerifiedCandidate() }
                throw UpdateOperationFailure(verified.code, manifest)
            }
        }
    }

    private fun publish(generation: Long, state: UpdateState) {
        synchronized(operationLock) {
            if (generation != operationGeneration) return
            mutableState.value = state
        }
    }

    private class UpdateOperationFailure(
        val code: UpdateFailureCode,
        val manifest: DevOtaManifest? = null,
    ) : Exception(code.name)

    companion object {
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024L
        private val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
            coerceInputValues = false
        }

        fun create(
            context: android.content.Context,
            firstPreviewGate: FirstPreviewGate,
        ): DevelopmentUpdateRepository {
            val applicationContext = context.applicationContext
            val inspector = AndroidApkInspector(applicationContext)
            return DevelopmentUpdateRepository(
                firstPreviewGate = firstPreviewGate,
                installedIdentitySource = InstalledIdentitySource(inspector::inspectInstalled),
                httpClient = HttpsDevelopmentHttpClient(),
                fileStore = UpdateFileStore(applicationContext.cacheDir),
                candidateVerifier = DownloadedCandidateVerifier { candidate, manifest ->
                    VerifiedApk.verifyAndPromote(candidate, manifest, inspector)
                },
            )
        }
    }
}
