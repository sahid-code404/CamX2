package com.sahidcode404.camx.core.camera.cache

import android.system.Os
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import com.sahidcode404.camx.core.camera.model.StableLensReferenceSnapshot
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

enum class DiscoveryCacheResetResult {
    SUCCESS,
    FAILED,
    NOTHING_TO_RESET,
}

/** API-23-safe atomic persistence for hot, topology, lens-reference, and bounded deep-discovery records. */
class AtomicCameraCachePersistence internal constructor(
    private val directory: File,
    private val fileSystem: CacheFileSystem = RealCacheFileSystem,
) : CameraCachePersistence {
    constructor(directory: File) : this(directory, RealCacheFileSystem)

    override suspend fun readHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot> =
        readBounded(hotFile, CacheBounds.HOT_FILE_BYTES) { HotStartCacheCodec.decode(it, environment) }

    override suspend fun readTopology(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<CameraTopologySnapshot> {
        val inspection = inspectTopologyThroughFileSystem(environment)
        TopologyCacheMigrationAudit.recordInspection(inspection)
        val result = readBounded(topologyFile, CacheBounds.TOPOLOGY_FILE_BYTES) {
            TopologyCacheCodec.decode(it, environment)
        }
        TopologyCacheMigrationAudit.recordRead(result)
        return result
    }

    internal suspend fun readStableLensReference(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<StableLensReferenceSnapshot> =
        readBounded(referenceFile, CacheBounds.REFERENCE_FILE_BYTES) {
            StableLensReferenceCacheCodec.decode(it, environment)
        }

    internal suspend fun readDeepKnowledgeInternal(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<DeepDiscoveryKnowledge> =
        readBounded(deepFile, CacheBounds.DEEP_FILE_BYTES) {
            DeepDiscoveryKnowledgeCodec.decode(it, environment)
        }

    override suspend fun writeHot(snapshot: HotStartSnapshot): CacheWrite =
        encodeAndWrite(hotFile, hotTempFile) { HotStartCacheCodec.encode(snapshot) }

    override suspend fun writeTopology(snapshot: CameraTopologySnapshot): CacheWrite {
        val result = encodeAndWrite(topologyFile, topologyTempFile) { TopologyCacheCodec.encode(snapshot) }
        TopologyCacheMigrationAudit.recordWrite(result)
        return result
    }

    internal suspend fun writeStableLensReference(snapshot: StableLensReferenceSnapshot): CacheWrite =
        encodeAndWrite(referenceFile, referenceTempFile) { StableLensReferenceCacheCodec.encode(snapshot) }

    internal suspend fun writeDeepKnowledgeInternal(knowledge: DeepDiscoveryKnowledge): CacheWrite =
        encodeAndWrite(deepFile, deepTempFile) { DeepDiscoveryKnowledgeCodec.encode(knowledge) }

    /** Clears discovery evidence only. Stable user/reference identity and the hot preview cache survive. */
    internal suspend fun resetDiscoveryCaches(): DiscoveryCacheResetResult {
        val targets = listOf(topologyFile, topologyTempFile, deepFile, deepTempFile)
        return try {
            val existing = targets.filter(fileSystem::exists)
            if (existing.isEmpty()) return DiscoveryCacheResetResult.NOTHING_TO_RESET
            if (existing.all(fileSystem::delete)) {
                DiscoveryCacheResetResult.SUCCESS
            } else {
                DiscoveryCacheResetResult.FAILED
            }
        } catch (_: Exception) {
            DiscoveryCacheResetResult.FAILED
        }
    }

    private fun inspectTopologyThroughFileSystem(
        environment: CameraEnvironmentFingerprint,
    ): TopologyCacheInspection {
        return try {
            if (!fileSystem.exists(topologyFile)) {
                return TopologyCacheInspection(TopologyCacheInspectionStatus.ABSENT)
            }
            val length = fileSystem.length(topologyFile)
            if (length <= 0L || length > CacheBounds.TOPOLOGY_FILE_BYTES.toLong()) {
                return TopologyCacheInspection(TopologyCacheInspectionStatus.CORRUPT)
            }
            val bytes = ByteArray(length.toInt())
            fileSystem.openInput(topologyFile).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) return TopologyCacheInspection(TopologyCacheInspectionStatus.CORRUPT)
                    if (read == 0) continue
                    offset += read
                }
                if (input.read() != -1) return TopologyCacheInspection(TopologyCacheInspectionStatus.CORRUPT)
            }
            TopologyCacheMigrationInspector.inspectBytes(bytes, environment)
        } catch (_: Exception) {
            TopologyCacheInspection(TopologyCacheInspectionStatus.IO_FAILURE)
        }
    }

    private val hotFile: File get() = File(directory, HOT_FILE_NAME)
    private val deepFile: File get() = File(directory, DEEP_FILE_NAME)
    private val referenceFile: File get() = File(directory, REFERENCE_FILE_NAME)
    private val topologyFile: File get() = File(directory, TOPOLOGY_FILE_NAME)
    private val hotTempFile: File get() = File(directory, "$HOT_FILE_NAME.tmp")
    private val deepTempFile: File get() = File(directory, "$DEEP_FILE_NAME.tmp")
    private val referenceTempFile: File get() = File(directory, "$REFERENCE_FILE_NAME.tmp")
    private val topologyTempFile: File get() = File(directory, "$TOPOLOGY_FILE_NAME.tmp")

    private fun <T> readBounded(
        file: File,
        maximumBytes: Int,
        decode: (ByteArray) -> CacheRead<T>,
    ): CacheRead<T> {
        return try {
            if (!fileSystem.exists(file)) return CacheRead.Miss
            val length = fileSystem.length(file)
            if (length <= 0L || length > maximumBytes.toLong()) {
                return CacheRead.Corrupt("Cache file size is outside bounds")
            }
            val bytes = ByteArray(length.toInt())
            fileSystem.openInput(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) return CacheRead.Corrupt("Cache file is truncated")
                    if (read == 0) continue
                    offset += read
                }
                if (input.read() != -1) return CacheRead.Corrupt("Cache file changed while being read")
            }
            decode(bytes)
        } catch (error: Exception) {
            CacheRead.IoFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun encodeAndWrite(
        authoritative: File,
        temporary: File,
        encode: () -> ByteArray,
    ): CacheWrite {
        val bytes = try {
            encode()
        } catch (error: IllegalArgumentException) {
            return CacheWrite.Rejected(error.message ?: "Snapshot exceeds cache format")
        }
        return writeAtomically(authoritative, temporary, bytes)
    }

    private fun writeAtomically(authoritative: File, temporary: File, bytes: ByteArray): CacheWrite {
        var sink: CacheOutputSink? = null
        var closeAttempted = false
        return try {
            fileSystem.ensureDirectory(directory)
            if (fileSystem.exists(temporary) && !fileSystem.delete(temporary)) {
                return CacheWrite.IoFailure("Cannot remove abandoned cache temp file")
            }
            sink = fileSystem.openOutput(temporary)
            sink.write(bytes)
            sink.flush()
            sink.sync()
            closeAttempted = true
            sink.close()
            sink = null
            fileSystem.rename(temporary, authoritative)
            CacheWrite.Success
        } catch (error: Exception) {
            try {
                if (!closeAttempted) sink?.close()
            } catch (_: Exception) {
                // The write already failed; cleanup below remains best-effort and never touches authority.
            }
            try {
                if (fileSystem.exists(temporary)) fileSystem.delete(temporary)
            } catch (_: Exception) {
                // Preserve the original failure. A later write will reject or remove the abandoned temp.
            }
            CacheWrite.IoFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        const val HOT_FILE_NAME = "camx-hot.cache"
        const val DEEP_FILE_NAME = "camx-deep.cache"
        const val REFERENCE_FILE_NAME = "camx-lens-reference.cache"
        const val TOPOLOGY_FILE_NAME = "camx-topology.cache"
    }
}

internal interface CacheOutputSink {
    fun write(bytes: ByteArray)
    fun flush()
    fun sync()
    fun close()
}

internal interface CacheFileSystem {
    fun ensureDirectory(directory: File)
    fun exists(file: File): Boolean
    fun length(file: File): Long
    fun openInput(file: File): InputStream
    fun openOutput(file: File): CacheOutputSink
    fun rename(source: File, destination: File)
    fun delete(file: File): Boolean
}

internal object RealCacheFileSystem : CacheFileSystem {
    override fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw java.io.IOException("Cannot create cache directory")
        }
    }

    override fun exists(file: File): Boolean = file.exists()

    override fun length(file: File): Long = file.length()

    override fun openInput(file: File): InputStream = FileInputStream(file)

    override fun openOutput(file: File): CacheOutputSink = FileCacheOutputSink(FileOutputStream(file, false))

    override fun rename(source: File, destination: File) {
        Os.rename(source.absolutePath, destination.absolutePath)
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}

private class FileCacheOutputSink(private val output: FileOutputStream) : CacheOutputSink {
    override fun write(bytes: ByteArray) = output.write(bytes)
    override fun flush() = output.flush()
    override fun sync() = output.fd.sync()
    override fun close() = output.close()
}
